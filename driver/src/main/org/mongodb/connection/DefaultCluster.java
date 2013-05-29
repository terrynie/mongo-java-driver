/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb.connection;

import org.mongodb.MongoClientOptions;
import org.mongodb.MongoCredential;
import org.mongodb.MongoInterruptedException;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mongodb.assertions.Assertions.isTrue;
import static org.mongodb.assertions.Assertions.notNull;

public abstract class DefaultCluster implements Cluster {

    private static final Logger LOGGER = Logger.getLogger("org.mongodb.connection");

    private final AtomicReference<CountDownLatch> phase = new AtomicReference<CountDownLatch>(new CountDownLatch(1));
    private final BufferPool<ByteBuffer> bufferPool;
    private final List<MongoCredential> credentialList;
    private final MongoClientOptions options;
    private final ClusterableServerFactory serverFactory;
    private final ScheduledExecutorService scheduledExecutorService;
    private final ThreadLocal<Random> random = new ThreadLocal<Random>() {
        @Override
        protected Random initialValue() {
            return new Random();
        }
    };

    private volatile boolean isClosed;
    private volatile ClusterDescription description;

    public DefaultCluster(final List<MongoCredential> credentialList,
                          final MongoClientOptions options, final ClusterableServerFactory serverFactory) {
        this.credentialList = credentialList;
        this.options = notNull("options", options);
        this.serverFactory = notNull("serverFactory", serverFactory);
        this.bufferPool = new PowerOfTwoByteBufferPool();
        scheduledExecutorService = Executors.newScheduledThreadPool(3);  // TODO: configurable
    }

    @Override
    public Server getServer(final ServerSelector serverSelector) {
        isTrue("open", !isClosed());

        try {
            CountDownLatch currentPhase = phase.get();
            ClusterDescription curDescription = description;
            List<ServerDescription> serverDescriptions = serverSelector.choose(curDescription);
            long endTime = System.nanoTime() + TimeUnit.NANOSECONDS.convert(20, TimeUnit.SECONDS); // TODO: configurable
            while (serverDescriptions.isEmpty()) {

                if (!curDescription.isConnecting()) {
                    throw new MongoServerSelectionFailureException(String.format("No server satisfies the selector %s",
                            serverSelector));
                }

                final long timeout = endTime - System.nanoTime();

                LOGGER.log(Level.FINE, String.format(
                        "No server chosen by %s from cluster description %s. Waiting for %d ms before timing out",
                        serverSelector, curDescription, TimeUnit.MILLISECONDS.convert(timeout, TimeUnit.NANOSECONDS)));

                if (!currentPhase.await(timeout, TimeUnit.NANOSECONDS)) {
                    throw new MongoTimeoutException(
                            "Timed out while waiting for a server that satisfies the selector: " + serverSelector);
                }
                currentPhase = phase.get();
                curDescription = description;
                serverDescriptions = serverSelector.choose(curDescription);
            }
            return new WrappedServer(getServer(getRandomServer(serverDescriptions).getAddress()));
        } catch (InterruptedException e) {
            throw new MongoInterruptedException(
                    String.format("Interrupted while waiting for a server that satisfies server selector %s ", serverSelector), e);
        }
    }

    @Override
    public ClusterDescription getDescription() {
        isTrue("open", !isClosed());

        try {
            CountDownLatch currentPhase = phase.get();
            ClusterDescription curDescription = description;
            long endTime = System.nanoTime() + TimeUnit.NANOSECONDS.convert(20, TimeUnit.SECONDS); // TODO: configurable
            while (curDescription.getType() == ClusterType.Unknown) {
                final long timeout = endTime - System.nanoTime();

                LOGGER.log(Level.FINE, String.format("Cluster description not yet available. Waiting for %d ms before timing out",
                        TimeUnit.MILLISECONDS.convert(timeout, TimeUnit.NANOSECONDS)));

                if (!currentPhase.await(timeout, TimeUnit.NANOSECONDS)) {
                    throw new MongoTimeoutException("Timed out while waiting for the cluster description");
                }
                currentPhase = phase.get();
                curDescription = description;
            }
            return curDescription;
        } catch (InterruptedException e) {
            throw new MongoInterruptedException(
                    String.format("Interrupted while waiting for the cluster description"), e);
        }
    }

    @Override
    public void close() {
        if (!isClosed()) {
            isClosed = true;
            scheduledExecutorService.shutdownNow();
            phase.get().countDown();
        }
    }

    @Override
    public boolean isClosed() {
        return isClosed;
    }

    protected abstract ClusterableServer getServer(final ServerAddress serverAddress);

    protected synchronized void updateDescription(final ClusterDescription newDescription) {
        LOGGER.log(Level.FINE, String.format("Updating cluster description %s and notifying all waiters", newDescription));

        description = newDescription;
        CountDownLatch current = phase.getAndSet(new CountDownLatch(1));
        current.countDown();
    }

    private ServerDescription getRandomServer(final List<ServerDescription> serverDescriptions) {
        return serverDescriptions.get(getRandom().nextInt(serverDescriptions.size()));
    }

    protected Random getRandom() {
        return random.get();
    }

    protected ClusterableServer createServer(final ServerAddress serverAddress, final ChangeListener<ServerDescription>
            serverStateListener) {
        final ClusterableServer server = serverFactory.create(serverAddress, credentialList, options, scheduledExecutorService,
                bufferPool);
        server.addChangeListener(serverStateListener);
        return server;
    }

    private static final class WrappedServer implements Server {
        private volatile ClusterableServer wrapped;

        public WrappedServer(final ClusterableServer server) {
            wrapped = server;
        }

        @Override
        public ServerDescription getDescription() {
            return wrapped.getDescription();
        }

        @Override
        public ServerConnection getConnection() {
            return wrapped.getConnection();
        }

        @Override
        public AsyncServerConnection getAsyncConnection() {
            return wrapped.getAsyncConnection();
        }
   }
}
