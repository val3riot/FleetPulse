package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpServerIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void receivesConsecutiveSimulatorCompatibleFramesOnPersistentConnection() throws Exception {
        BlockingQueue<TelemetryMessage> received = new LinkedBlockingQueue<>();
        try (RunningServer running = RunningServer.start(received::add); Socket client = connect(running.port())) {
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 1);
            TelemetryMessage first = message(42);
            TelemetryMessage second = message(43);

            write(client, first);
            write(client, second);

            assertEquals(first, received.poll(2, TimeUnit.SECONDS));
            assertEquals(second, received.poll(2, TimeUnit.SECONDS));
            await(() -> metric(running.registry(), "fleetpulse.gateway.frames.received") == 2);
            assertEquals(1, metric(running.registry(), "fleetpulse.gateway.connections.accepted"));
            assertEquals(0, metric(running.registry(), "fleetpulse.gateway.connections.failures"));
        }
    }

    @Test
    void handlesConcurrentRealClientsAndUpdatesMetrics() throws Exception {
        int clientCount = 4;
        CountDownLatch handled = new CountDownLatch(clientCount);
        try (RunningServer running = RunningServer.start(message -> handled.countDown())) {
            List<Socket> clients = new ArrayList<>();
            try {
                for (int index = 0; index < clientCount; index++) {
                    Socket client = connect(running.port());
                    clients.add(client);
                    write(client, message(index));
                }

                assertTrue(handled.await(2, TimeUnit.SECONDS));
                await(() -> metric(running.registry(), "fleetpulse.gateway.connections.accepted") == clientCount);
                assertEquals(clientCount, metric(running.registry(), "fleetpulse.gateway.frames.received"));
                assertEquals(clientCount, metric(running.registry(), "fleetpulse.gateway.connections.active"));
            } finally {
                for (Socket client : clients) {
                    client.close();
                }
            }
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 0);
        }
    }

    @Test
    void recordsFailureAndCleansUpWhenClientDisconnectsMidFrame() throws Exception {
        try (RunningServer running = RunningServer.start(message -> {
        }); Socket client = connect(running.port())) {
            DataOutputStream output = new DataOutputStream(client.getOutputStream());
            output.writeInt(10);
            output.write(new byte[]{'{', '}'});
            output.flush();
            client.close();

            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.failures") == 1);
            assertEquals(0, metric(running.registry(), "fleetpulse.gateway.frames.received"));
            assertEquals(0, metric(running.registry(), "fleetpulse.gateway.connections.active"));
        }
    }

    @Test
    void shutdownClosesAnActiveRealClientAndResetsGauge() throws Exception {
        RunningServer running = RunningServer.start(
                message -> { },
                100,
                Duration.ofSeconds(5),
                Duration.ofMillis(200)
        );
        try (Socket client = connect(running.port())) {
            client.setSoTimeout(2_000);
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 1);

            running.close();

            assertEquals(-1, client.getInputStream().read());
            assertEquals(0, metric(running.registry(), "fleetpulse.gateway.connections.active"));
            assertEquals(0, metric(running.registry(), "fleetpulse.gateway.connections.failures"));
        } finally {
            running.close();
        }
    }

    @Test
    void rejectsConnectionsBeyondCapacityAndReusesPermitAfterDisconnect() throws Exception {
        try (RunningServer running = RunningServer.start(message -> {
        }, 1); Socket first = connect(running.port())) {
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 1);
            try (Socket rejected = connect(running.port())) {
                rejected.setSoTimeout(2_000);
                assertEquals(-1, rejected.getInputStream().read());
            }
            await(() -> metric(running.registry(), "fleetpulse.gateway.tcp.connections.capacity.rejected") == 1);
            assertEquals(1, metric(running.registry(), "fleetpulse.gateway.connections.accepted"));
            assertEquals(0, metric(running.registry(), "fleetpulse.gateway.connections.rejected"));
            assertEquals(1, metric(running.registry(), "fleetpulse.gateway.connections.active"));

            first.close();
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 0);

            try (Socket replacement = connect(running.port())) {
                await(() -> metric(running.registry(), "fleetpulse.gateway.connections.accepted") == 2);
                assertEquals(1, metric(running.registry(), "fleetpulse.gateway.connections.active"));
            }
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 0);
        }
    }

    @Test
    void returnsPermitAfterDecoderFailure() throws Exception {
        try (RunningServer running = RunningServer.start(message -> {
        }, 1)) {
            try (Socket invalid = connect(running.port())) {
                DataOutputStream output = new DataOutputStream(invalid.getOutputStream());
                output.writeInt(10);
                output.write(new byte[]{'{', '}'});
                output.flush();
            }
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.failures") == 1);
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 0);

            try (Socket replacement = connect(running.port())) {
                await(() -> metric(running.registry(), "fleetpulse.gateway.connections.accepted") == 2);
                assertEquals(1, metric(running.registry(), "fleetpulse.gateway.connections.active"));
                assertEquals(0, metric(running.registry(), "fleetpulse.gateway.tcp.connections.capacity.rejected"));
            }
        }
    }

    @Test
    void returnsPermitAfterFrameHandlerFailure() throws Exception {
        try (RunningServer running = RunningServer.start(message -> {
            throw new IllegalStateException("handler failure");
        }, 1)) {
            try (Socket failing = connect(running.port())) {
                write(failing, message(1));
                await(() -> metric(running.registry(), "fleetpulse.gateway.connections.failures") == 1);
            }
            await(() -> metric(running.registry(), "fleetpulse.gateway.connections.active") == 0);

            try (Socket replacement = connect(running.port())) {
                await(() -> metric(running.registry(), "fleetpulse.gateway.connections.accepted") == 2);
                assertEquals(1, metric(running.registry(), "fleetpulse.gateway.connections.active"));
                assertEquals(0, metric(running.registry(), "fleetpulse.gateway.tcp.connections.capacity.rejected"));
            }
        }
    }

    @Test
    void neverExceedsMaximumConnectionsUnderConcurrentLoad() throws Exception {
        int maxConnections = 3;
        int clientCount = 10;
        try (RunningServer running = RunningServer.start(message -> {
        }, maxConnections)) {
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<Socket>> attempts = new ArrayList<>();
            for (int index = 0; index < clientCount; index++) {
                attempts.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return connect(running.port());
                    } catch (IOException exception) {
                        throw new IllegalStateException(exception);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }));
            }

            start.countDown();
            List<Socket> clients = attempts.stream().map(future -> future.orTimeout(2, TimeUnit.SECONDS).join()).toList();
            AtomicInteger observedMaximum = new AtomicInteger();
            try {
                await(() -> {
                    int active = (int) metric(running.registry(), "fleetpulse.gateway.connections.active");
                    observedMaximum.accumulateAndGet(active, Math::max);
                    assertTrue(active <= maxConnections);
                    double completed = metric(running.registry(), "fleetpulse.gateway.connections.accepted") + metric(running.registry(), "fleetpulse.gateway.tcp.connections.capacity.rejected");
                    return completed == clientCount;
                });
                assertEquals(maxConnections, metric(running.registry(), "fleetpulse.gateway.connections.accepted"));
                assertEquals(clientCount - maxConnections, metric(running.registry(), "fleetpulse.gateway.tcp.connections.capacity.rejected"));
                assertTrue(observedMaximum.get() <= maxConnections);
            } finally {
                for (Socket client : clients) {
                    client.close();
                }
            }
        }
    }

    @Test
    void closesIdleClientWhenReadTimeoutExpires() throws Exception {
        try (
                RunningServer running = RunningServer.start(
                        message -> {
                        },
                        1,
                        Duration.ofMillis(200),
                        Duration.ofSeconds(1)
                );
                Socket client = connect(running.port())
        ) {

            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 1
            );
            // ASPETTA TIMEOUT
            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 0
            );

            client.setSoTimeout(1_000);

            assertEquals(
                    -1,
                    client.getInputStream().read()
            );
            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    )
            );
            assertEquals(
                    1,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.timeouts"
                    )
            );
            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.failures"
                    )
            );
        }
    }
    @Test
    void reusesPermitAfterReadTimeout() throws Exception {
        try (
                RunningServer running = RunningServer.start(
                        message -> { },
                        1,
                        Duration.ofMillis(200),
                        Duration.ofSeconds(1)
                )
        ) {
            try (Socket first = connect(running.port())) {
                await(() ->
                        metric(
                                running.registry(),
                                "fleetpulse.gateway.connections.active"
                        ) == 1
                );

                await(() ->
                        metric(
                                running.registry(),
                                "fleetpulse.gateway.connections.timeouts"
                        ) == 1
                );

                await(() ->
                        metric(
                                running.registry(),
                                "fleetpulse.gateway.connections.active"
                        ) == 0
                );

                assertEquals(
                        0,
                        metric(
                                running.registry(),
                                "fleetpulse.gateway.tcp.connections.capacity.rejected"
                        )
                );
            }

            try (Socket replacement = connect(running.port())) {
                await(() ->
                        metric(
                                running.registry(),
                                "fleetpulse.gateway.connections.accepted"
                        ) == 2
                );

                assertEquals(
                        0,
                        metric(
                                running.registry(),
                                "fleetpulse.gateway.tcp.connections.capacity.rejected"
                        )
                );
            }
        }
    }

    @Test
    void closesClientWhenReadTimeoutExpiresDuringPartialFrame() throws Exception {
        try (
                RunningServer running = RunningServer.start(
                        message -> { },
                        1,
                        Duration.ofMillis(200),
                        Duration.ofSeconds(1)
                );
                Socket client = connect(running.port())
        ) {
            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 1
            );

            DataOutputStream output =
                    new DataOutputStream(client.getOutputStream());

            output.writeInt(10);
            output.write(new byte[]{'{', '}'});
            output.flush();

            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.timeouts"
                    ) == 1
            );

            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 0
            );

            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.failures"
                    )
            );
        }
    }

    @Test
    void keepsConnectionAliveWhileFramesArriveBeforeReadTimeout() throws Exception {
        int frameCount = 6;
        CountDownLatch handled = new CountDownLatch(frameCount);

        try (
                RunningServer running = RunningServer.start(
                        message -> handled.countDown(),
                        1,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)
                );
                Socket client = connect(running.port())
        ) {
            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 1
            );

            for (int index = 0; index < frameCount; index++) {
                write(client, message(index));

                if (index < frameCount - 1) {
                    Thread.sleep(250);
                }
            }

            assertTrue(handled.await(1, TimeUnit.SECONDS));

            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.frames.received"
                    ) == frameCount
            );

            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.timeouts"
                    )
            );

            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.failures"
                    )
            );

            assertEquals(
                    1,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    )
            );
        }
    }

    @Test
    void allowsInFlightHandlerToFinishWithinGracePeriod() throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);

        RunningServer running = RunningServer.start(
                message -> {
                    handlerStarted.countDown();

                    try {
                        releaseHandler.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                },
                1,
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );

        try (Socket client = connect(running.port())) {
            write(client, message(1));

            /*
             * Dopo il frame segnaliamo EOF lato client.
             * Quando il handler verrà rilasciato, il loop TCP potrà
             * leggere l'EOF e terminare naturalmente.
             */
            client.shutdownOutput();

            assertTrue(
                    handlerStarted.await(1, TimeUnit.SECONDS),
                    "handler did not start"
            );

            CompletableFuture<Void> closing =
                    CompletableFuture.runAsync(running.server::close);

            /*
             * Il close NON deve terminare immediatamente:
             * il handler è ancora in-flight e siamo dentro la grace window.
             */
            assertThrows(
                    TimeoutException.class,
                    () -> closing.get(150, TimeUnit.MILLISECONDS)
            );

            releaseHandler.countDown();

            closing.get(2, TimeUnit.SECONDS);

            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 0
            );

            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.failures"
                    )
            );
        } finally {
            /*
             * Evita di lasciare il handler bloccato anche in caso
             * di assertion failure.
             */
            releaseHandler.countDown();
            running.close();
        }
    }

    @Test
    void forceClosesClientWhenGracePeriodExpires() throws Exception {
        RunningServer running = RunningServer.start(
                message -> { },
                1,
                Duration.ofSeconds(5),
                Duration.ofMillis(200)
        );

        try (Socket client = connect(running.port())) {
            client.setSoTimeout(2_000);

            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 1
            );

            CompletableFuture<Void> closing =
                    CompletableFuture.runAsync(running.server::close);

            /*
             * readTimeout server = 5s
             * gracePeriod = 200ms
             *
             * Se il graceful shutdown funziona, la socket deve essere
             * force-closed molto prima dei 5 secondi.
             */
            assertEquals(
                    -1,
                    client.getInputStream().read()
            );

            closing.get(2, TimeUnit.SECONDS);

            await(() ->
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.active"
                    ) == 0
            );

            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.timeouts"
                    )
            );

            assertEquals(
                    0,
                    metric(
                            running.registry(),
                            "fleetpulse.gateway.connections.failures"
                    )
            );
        } finally {
            running.close();
        }
    }

    private static Socket connect(int port) throws IOException {
        return new Socket(InetAddress.getLoopbackAddress(), port);
    }

    private static void write(Socket client, TelemetryMessage message) throws IOException {
        LengthPrefixedFrameCodec.write(OBJECT_MAPPER.writeValueAsBytes(message), client.getOutputStream());
    }

    private static TelemetryMessage message(long sequenceNumber) {
        return new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION, UUID.randomUUID(), UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), sequenceNumber, Instant.parse("2026-08-01T10:15:30Z"), 72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
    }

    private static double metric(SimpleMeterRegistry registry, String name) {
        return registry.get(name).meter().measure().iterator().next().getValue();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static final class RunningServer implements AutoCloseable {

        private final TcpServer server;
        private final SimpleMeterRegistry registry;
        private final Thread listener;
        private final AtomicReference<Throwable> listenerFailure;
        private final int port;
        private boolean closed;

        private RunningServer(TcpServer server, SimpleMeterRegistry registry, Thread listener, AtomicReference<Throwable> listenerFailure, int port) {
            this.server = server;
            this.registry = registry;
            this.listener = listener;
            this.listenerFailure = listenerFailure;
            this.port = port;
        }

        static RunningServer start(FrameHandler handler) throws Exception {
            return start(
                    handler,
                    100,
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(5)
            );
        }

        static RunningServer start(
                FrameHandler handler,
                int maxConnections
        ) throws Exception {
            return start(
                    handler,
                    maxConnections,
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(5)
            );
        }

        static RunningServer start(
                FrameHandler handler,
                int maxConnections,
                Duration readTimeout,
                Duration shutdownGracePeriod
        ) throws Exception {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();

            TcpServer server = new TcpServer(
                    handler,
                    new TcpServerProperties(true, 0, maxConnections, readTimeout, shutdownGracePeriod),
                    new FrameDecoder(OBJECT_MAPPER),
                    registry);
            CompletableFuture<Integer> bindResult = new CompletableFuture<>();
            AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
            Thread listener = Thread.ofPlatform().name("tcp-integration-test-listener").start(() -> {
                try {
                    server.start(bindResult);
                } catch (Throwable exception) {
                    listenerFailure.set(exception);
                    bindResult.completeExceptionally(exception);
                }
            });
            int port = bindResult.get(2, TimeUnit.SECONDS);
            return new RunningServer(server, registry, listener, listenerFailure, port);
        }

        int port() {
            return port;
        }

        SimpleMeterRegistry registry() {
            return registry;
        }

        @Override
        public void close() throws Exception {
            if (closed) {
                return;
            }
            closed = true;
            server.close();
            listener.join(2_000);
            assertTrue(!listener.isAlive(), "TCP listener did not stop");
            if (listenerFailure.get() != null) {
                throw new AssertionError("TCP listener failed", listenerFailure.get());
            }
            registry.close();
        }
    }
}
