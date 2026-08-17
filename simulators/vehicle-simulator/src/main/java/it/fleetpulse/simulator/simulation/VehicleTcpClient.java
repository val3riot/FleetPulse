package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.simulator.tcp.TelemetryFrameEncoder;

import javax.net.SocketFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;

public final class VehicleTcpClient implements VehicleConnection {

    private final String host;
    private final int port;
    private final TelemetryFrameEncoder encoder;
    private final SocketFactory socketFactory;
    private final int connectTimeoutMillis;
    private final Object lifecycleMonitor = new Object();

    private volatile Socket socket;
    private volatile OutputStream output;

    public VehicleTcpClient(String host, int port, TelemetryFrameEncoder encoder,
        SocketFactory socketFactory) {
        this(host, port, encoder, socketFactory, Duration.ofSeconds(3));
    }

    public VehicleTcpClient(String host, int port, TelemetryFrameEncoder encoder,
        SocketFactory socketFactory, Duration connectTimeout) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }
        this.host = host;
        this.port = port;
        this.encoder = Objects.requireNonNull(encoder, "encoder");
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be greater than zero");
        }
        if (connectTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("connectTimeout is too large");
        }
        this.connectTimeoutMillis = Math.toIntExact(connectTimeout.toMillis());
    }

    @Override
    public void connect() throws IOException {
        Socket candidate;
        synchronized (lifecycleMonitor) {
            if (isConnected()) {
                return;
            }

            closeLocked();
            candidate = socketFactory.createSocket();
            socket = candidate;
        }

        try {
            candidate.connect(new InetSocketAddress(host, port), connectTimeoutMillis);
            OutputStream candidateOutput = candidate.getOutputStream();
            synchronized (lifecycleMonitor) {
                if (socket != candidate || candidate.isClosed()) {
                    throw new SocketException("Vehicle TCP connection was closed during connect");
                }
                socket = candidate;
                output = candidateOutput;
            }
        } catch (IOException exception) {
            closeIfCurrent(candidate);
            throw exception;
        }
    }

    @Override
    public void send(TelemetryMessage message) throws IOException {
        Socket currentSocket = socket;
        OutputStream currentOutput = output;
        if (!isUsable(currentSocket, currentOutput)) {
            throw new SocketException("Vehicle TCP connection is not open");
        }

        try {
            encoder.write(message, currentOutput);
        } catch (IOException exception) {
            closeIfCurrent(currentSocket);
            throw exception;
        }
    }

    @Override
    public boolean isConnected() {
        return isUsable(socket, output);
    }

    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            closeLocked();
        }
    }

    private void closeIfCurrent(Socket expectedSocket) {
        synchronized (lifecycleMonitor) {
            if (socket == expectedSocket) {
                closeLocked();
            }
        }
    }

    private void closeLocked() {
        Socket socketToClose = socket;
        socket = null;
        output = null;
        if (socketToClose != null) {
            try {
                socketToClose.close();
            } catch (IOException ignored) {
                // AutoCloseable cannot surface cleanup failures to the simulator lifecycle.
            }
        }
    }

    private static boolean isUsable(Socket socket, OutputStream output) {
        return socket != null && output != null && socket.isConnected() && !socket.isClosed() &&
            !socket.isOutputShutdown();
    }
}
