package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import it.fleetpulse.simulator.tcp.TelemetryFrameEncoder;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.net.SocketFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleTcpClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TelemetryMessage MESSAGE =
        new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION,
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), 42,
            Instant.parse("2026-08-01T10:15:30Z"), 72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964);

    @Test
    void keepsOneConnectionOpenAcrossMultipleMessages() throws Exception {
        RecordingSocket socket = new RecordingSocket();
        RecordingSocketFactory socketFactory = new RecordingSocketFactory(socket);
        VehicleTcpClient client =
            new VehicleTcpClient("gateway", 7000, new TelemetryFrameEncoder(OBJECT_MAPPER),
                socketFactory);

        client.connect();
        client.connect();
        client.send(MESSAGE);
        client.send(MESSAGE);

        assertTrue(client.isConnected());
        assertEquals(1, socketFactory.createdSockets);
        ByteArrayInputStream frames = new ByteArrayInputStream(socket.bytes());
        assertEquals(MESSAGE, decodeFrame(frames));
        assertEquals(MESSAGE, decodeFrame(frames));
        assertEquals(0, frames.available());
    }

    @Test
    void rejectsSendBeforeConnection() {
        VehicleTcpClient client =
            new VehicleTcpClient("gateway", 7000, new TelemetryFrameEncoder(OBJECT_MAPPER),
                new RecordingSocketFactory(new RecordingSocket()));

        assertThrows(SocketException.class, () -> client.send(MESSAGE));
    }

    @Test
    void closeReleasesSocketAndAllowsANewConnection() throws Exception {
        RecordingSocket first = new RecordingSocket();
        RecordingSocket second = new RecordingSocket();
        RecordingSocketFactory socketFactory = new RecordingSocketFactory(first, second);
        VehicleTcpClient client =
            new VehicleTcpClient("gateway", 7000, new TelemetryFrameEncoder(OBJECT_MAPPER),
                socketFactory);

        client.connect();
        client.close();

        assertTrue(first.isClosed());
        assertFalse(client.isConnected());

        client.connect();
        client.send(MESSAGE);

        assertTrue(client.isConnected());
        assertEquals(2, socketFactory.createdSockets);
        assertEquals(MESSAGE, decodeFrame(new ByteArrayInputStream(second.bytes())));
    }

    @Test
    void validatesConnectionSettings() {
        TelemetryFrameEncoder encoder = new TelemetryFrameEncoder(OBJECT_MAPPER);
        SocketFactory socketFactory = new RecordingSocketFactory(new RecordingSocket());

        assertThrows(IllegalArgumentException.class,
            () -> new VehicleTcpClient(" ", 7000, encoder, socketFactory));
        assertThrows(IllegalArgumentException.class,
            () -> new VehicleTcpClient("gateway", 0, encoder, socketFactory));
        assertThrows(NullPointerException.class,
            () -> new VehicleTcpClient("gateway", 7000, null, socketFactory));
        assertThrows(NullPointerException.class,
            () -> new VehicleTcpClient("gateway", 7000, encoder, null));
    }

    @Test
    void closeUnblocksAnInFlightWrite() throws Exception {
        BlockingSocket socket = new BlockingSocket();
        VehicleTcpClient client =
            new VehicleTcpClient("gateway", 7000, new TelemetryFrameEncoder(OBJECT_MAPPER),
                new RecordingSocketFactory(socket));
        client.connect();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var send = executor.submit(() -> {
                client.send(MESSAGE);
                return null;
            });
            assertTrue(socket.writeStarted.await(2, TimeUnit.SECONDS));

            client.close();

            assertThrows(ExecutionException.class, () -> send.get(2, TimeUnit.SECONDS));
            assertTrue(socket.isClosed());
            assertFalse(client.isConnected());
        }
    }

    private static TelemetryMessage decodeFrame(InputStream input) throws IOException {
        return OBJECT_MAPPER.readValue(LengthPrefixedFrameCodec.read(input),
            TelemetryMessage.class);
    }

    private static final class RecordingSocketFactory extends SocketFactory {
        private final Socket[] sockets;
        private int createdSockets;

        private RecordingSocketFactory(Socket... sockets) {
            this.sockets = sockets;
        }

        @Override
        public Socket createSocket() throws IOException {
            if (createdSockets == sockets.length) {
                throw new IOException("No test socket configured");
            }
            return sockets[createdSockets++];
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return createSocket();
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress host, int port) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
            int localPort) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingSocket extends Socket {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private boolean closed;

        @Override
        public ByteArrayOutputStream getOutputStream() throws IOException {
            if (closed) {
                throw new SocketException("Socket is closed");
            }
            return output;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
            // Test socket: creation and connect are modeled separately.
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public boolean isOutputShutdown() {
            return closed;
        }

        @Override
        public synchronized void close() {
            closed = true;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static final class BlockingSocket extends Socket {
        private final CountDownLatch writeStarted = new CountDownLatch(1);
        private final CountDownLatch closedSignal = new CountDownLatch(1);
        private final OutputStream output = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                writeStarted.countDown();
                try {
                    closedSignal.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Write interrupted", interrupted);
                }
                throw new SocketException("Socket closed during write");
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                write(0);
            }
        };
        private volatile boolean closed;

        @Override
        public OutputStream getOutputStream() {
            return output;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
            // Test socket: creation and connect are modeled separately.
        }

        @Override
        public boolean isConnected() {
            return !closed;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public boolean isOutputShutdown() {
            return closed;
        }

        @Override
        public void close() {
            closed = true;
            closedSignal.countDown();
        }
    }
}
