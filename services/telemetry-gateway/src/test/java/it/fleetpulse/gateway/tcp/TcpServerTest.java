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
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpServerTest {

    @Test
    void closesAndRemovesClientWhenExecutorRejectsDuringShutdown() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TestSocket client = new TestSocket();
        TcpServer server = new TcpServer(
                message -> { },
                new TcpServerProperties(true, 0),
                new FrameDecoder(new ObjectMapper()),
                executor,
                registry
        );

        server.dispatchClient(client);

        assertTrue(client.isClosed());
        assertEquals(0, server.activeClients());
        assertEquals(1, registry.counter("fleetpulse.gateway.connections.rejected").count());
        assertEquals(0, registry.get("fleetpulse.gateway.connections.active").gauge().value());
    }

    @Test
    void recordsUnexpectedFrameHandlerFailureAndCleansUpClient() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        TestSocket client = new TestSocket(frame(objectMapper, validMessage()));
        TcpServer server = new TcpServer(
                message -> { throw new IllegalStateException("unexpected handler failure"); },
                new TcpServerProperties(true, 0),
                new FrameDecoder(objectMapper),
                executor,
                registry
        );

        server.dispatchClient(client);
        executor.shutdown();
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));

        assertTrue(client.isClosed());
        assertEquals(0, server.activeClients());
        assertEquals(1, registry.counter("fleetpulse.gateway.connections.failures").count());
        assertEquals(1, registry.counter("fleetpulse.gateway.frames.received").count());
    }

    private static InputStream frame(ObjectMapper objectMapper, TelemetryMessage message) throws IOException {
        byte[] payload = objectMapper.writeValueAsBytes(message);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LengthPrefixedFrameCodec.write(payload, output);
        return new ByteArrayInputStream(output.toByteArray());
    }

    private static TelemetryMessage validMessage() {
        return new TelemetryMessage(
                ProtocolConstants.PROTOCOL_VERSION,
                UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                42,
                Instant.parse("2026-08-01T10:15:30Z"),
                72.4,
                91.8,
                12.6,
                85312,
                41.9028,
                12.4964
        );
    }

    private static final class TestSocket extends Socket {

        private final InputStream input;
        private volatile boolean closed;

        private TestSocket() {
            this(new ByteArrayInputStream(new byte[0]));
        }

        private TestSocket(InputStream input) {
            this.input = input;
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }
    }
}
