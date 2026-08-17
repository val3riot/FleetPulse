package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TcpServerTest {

    @Test
    void closesAndRemovesClientWhenExecutorRejectsDuringShutdown() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestSocket client = new TestSocket();
        TcpServer server = new TcpServer(TestAcknowledgements::accepted,
            new TcpServerProperties(true, 0, 1, Duration.ofSeconds(10), Duration.ofSeconds(5)),
            new FrameDecoder(new ObjectMapper()), new TelemetryAckEncoder(new ObjectMapper()),
            executor, registry);

        server.dispatchClient(client);

        assertTrue(client.isClosed());
        assertEquals(0, server.activeClients());
        assertEquals(1, registry.counter("fleetpulse.gateway.connections.rejected").count());
        assertEquals(0,
            registry.counter("fleetpulse.gateway.tcp.connections.capacity.rejected").count());
        assertEquals(0, registry.get("fleetpulse.gateway.connections.active").gauge().value());

        TestSocket nextClient = new TestSocket();
        server.dispatchClient(nextClient);

        assertTrue(nextClient.isClosed());
        assertEquals(2, registry.counter("fleetpulse.gateway.connections.rejected").count());
        assertEquals(0,
            registry.counter("fleetpulse.gateway.tcp.connections.capacity.rejected").count());
    }

    @Test
    void recordsUnexpectedFrameHandlerFailureAndCleansUpClient() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        TestSocket client = new TestSocket(frame(objectMapper, validMessage()));
        TcpServer server = new TcpServer(message -> {
            throw new IllegalStateException("unexpected handler failure");
        }, new TcpServerProperties(true, 0, 1, Duration.ofSeconds(10), Duration.ofSeconds(5)),
            new FrameDecoder(objectMapper), new TelemetryAckEncoder(objectMapper), executor,
            registry);

        server.dispatchClient(client);
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

        assertTrue(client.isClosed());
        assertEquals(0, server.activeClients());
        assertEquals(1, registry.counter("fleetpulse.gateway.connections.failures").count());
        assertEquals(1, registry.counter("fleetpulse.gateway.frames.received").count());
    }

    @Test
    void closesClientAndReleasesPermitWhenSocketTimeoutConfigurationFails() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        TcpServer server = new TcpServer(TestAcknowledgements::accepted,
            new TcpServerProperties(true, 0, 1, Duration.ofSeconds(10), Duration.ofSeconds(5)),
            new FrameDecoder(objectMapper), new TelemetryAckEncoder(objectMapper), executor,
            registry);

        TestSocket failingClient = new TestSocket(true);
        server.dispatchClient(failingClient);
        assertEquals(0, server.activeClients());
        assertEquals(1, registry.counter("fleetpulse.gateway.connections.failures").count());
        assertEquals(0, registry.counter("fleetpulse.gateway.connections.accepted").count());
        assertEquals(0,
            registry.counter("fleetpulse.gateway.tcp.connections.capacity.rejected").count());

        TestSocket nextClient = new TestSocket();
        server.dispatchClient(nextClient);

        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        assertEquals(1, registry.counter("fleetpulse.gateway.connections.accepted").count());
        assertEquals(0,
            registry.counter("fleetpulse.gateway.tcp.connections.capacity.rejected").count());
    }

    @Test
    void closeIsIdempotent() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        TcpServer server = new TcpServer(TestAcknowledgements::accepted,
            new TcpServerProperties(true, 0, 1, Duration.ofSeconds(10), Duration.ofSeconds(1)),
            new FrameDecoder(new ObjectMapper()), new TelemetryAckEncoder(new ObjectMapper()),
            executor, registry);

        assertDoesNotThrow(() -> {
            server.close();
            server.close();
        });

        assertTrue(executor.isShutdown());
        assertEquals(0, server.activeClients());
    }


    private static InputStream frame(ObjectMapper objectMapper,
        TelemetryMessage message) throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(message);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LengthPrefixedFrameCodec.write(payload, output);
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static TelemetryMessage validMessage() {
        return new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION,
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), 42,
            Instant.parse("2026-08-01T10:15:30Z"), 72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
    }

    private static final class TestSocket extends Socket {

        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private volatile boolean closed;
        private final boolean failOnSetSoTimeout;

        private TestSocket() {
            this(new ByteArrayInputStream(new byte[0]), false);
        }

        private TestSocket(InputStream input) {
            this(input, false);
        }

        private TestSocket(InputStream input, boolean failOnSetSoTimeout) {
            this.input = input;
            this.failOnSetSoTimeout = failOnSetSoTimeout;
        }

        private TestSocket(boolean failOnSetSoTimeout) {
            this(new ByteArrayInputStream(new byte[0]), failOnSetSoTimeout);
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return output;
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public synchronized void setSoTimeout(int timeout) throws SocketException {

            if (failOnSetSoTimeout) {
                throw new SocketException("test timeout configuration failure");
            }
        }
    }
}
