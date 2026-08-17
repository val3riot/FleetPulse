package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.FrameStreamClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);
    private volatile ServerSocket serverSocket;
    private final ExecutorService executor;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final FrameDecoder frameDecoder;
    private final FrameHandler frameHandler;
    private final TcpServerProperties properties;
    private final TcpServerMetrics metrics;
    private final Semaphore connectionPermits;
    private final TelemetryAckEncoder acknowledgementEncoder;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private static final int FORCE_SHUTDOWN_TIMEOUT_SECONDS = 1;

    public TcpServer(FrameHandler frameHandler, TcpServerProperties properties,
        FrameDecoder frameDecoder, TelemetryAckEncoder acknowledgementEncoder,
        MeterRegistry meterRegistry) {
        this(frameHandler, properties, frameDecoder, acknowledgementEncoder,
            Executors.newVirtualThreadPerTaskExecutor(), meterRegistry);
    }

    TcpServer(FrameHandler frameHandler, TcpServerProperties properties, FrameDecoder frameDecoder,
        TelemetryAckEncoder acknowledgementEncoder, ExecutorService executor,
        MeterRegistry meterRegistry) {
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler must not be null");
        this.frameDecoder = Objects.requireNonNull(frameDecoder, "frameDecoder must not be null");
        this.acknowledgementEncoder = Objects.requireNonNull(acknowledgementEncoder,
            "acknowledgementEncoder must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.metrics = new TcpServerMetrics(
            Objects.requireNonNull(meterRegistry, "meterRegistry must not be null"), clients);
        this.connectionPermits = new Semaphore(properties.maxConnections());
    }

    public void start(CompletableFuture<Integer> bindResult) throws IOException {
        Objects.requireNonNull(bindResult, "bindResult must not be null");
        try {
            serverSocket = new ServerSocket(properties.port());
            bindResult.complete(serverSocket.getLocalPort());
        } catch (IOException exception) {
            bindResult.completeExceptionally(exception);
            throw exception;
        }
        log.info("TCP server listening: port={}, activeClients={}", serverSocket.getLocalPort(),
            clients.size());
        try {
            while (!serverSocket.isClosed()) {
                acceptClient();
            }
        } catch (SocketException exception) {
            if (serverSocket.isClosed()) {
                log.debug("TCP accept loop stopped because the server socket was closed");
                return;
            }
            throw exception;
        }
    }

    private void acceptClient() throws IOException {
        Socket client = serverSocket.accept();
        dispatchClient(client);
    }

    void dispatchClient(Socket client) {
        Objects.requireNonNull(client, "client must not be null");
        if (!connectionPermits.tryAcquire()) {
            metrics.connectionCapacityRejected();
            closeRejectedClient(client);
            log.warn("TCP connection rejected because capacity is exhausted: remote={}, " +
                    "activeClients={}, maxConnections={}, capacityRejectedConnections={}",
                client.getRemoteSocketAddress(), clients.size(), properties.maxConnections(),
                metrics.capacityRejectedConnections());
            return;
        }
        try {
            client.setSoTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        } catch (SocketException exception) {
            connectionPermits.release();
            metrics.connectionFailed();
            closeRejectedClient(client);
            log.warn("Unable to configure TCP client read timeout: remote={}, readTimeout={}, " +
                    "connectionFailures={}", client.getRemoteSocketAddress(),
                properties.readTimeout(),
                metrics.connectionFailures(), exception);
            return;
        }
        clients.add(client);
        try {
            executor.submit(() -> handleClient(client));
            metrics.connectionAccepted();
            log.debug("TCP client accepted: remote={}, activeClients={}",
                client.getRemoteSocketAddress(), clients.size());
        } catch (RejectedExecutionException exception) {
            clients.remove(client);
            connectionPermits.release();
            metrics.connectionRejected();
            closeRejectedClient(client);
            log.debug("TCP client rejected during shutdown: remote={}, activeClients={}, " +
                    "rejectedConnections={}", client.getRemoteSocketAddress(), clients.size(),
                metrics.rejectedConnections());
        }
    }

    int activeClients() {
        return clients.size();
    }

    private void handleClient(Socket client) {
        try (client) {
            InputStream inputStream = client.getInputStream();
            OutputStream outputStream = client.getOutputStream();
            while (!Thread.currentThread().isInterrupted()) {
                TelemetryMessage message = frameDecoder.read(inputStream);
                metrics.frameReceived();
                log.debug(
                    "TCP frame received: remote={}, messageId={}, vehicleId={}, activeClients={}," +
                        " receivedFrames={}", client.getRemoteSocketAddress(), message.messageId(),
                    message.vehicleId(), clients.size(), metrics.receivedFrames());
                try {
                    TelemetryAck ack = frameHandler.handle(message);
                    acknowledgementEncoder.write(ack, outputStream);
                } catch (RuntimeException exception) {
                    metrics.connectionFailed();
                    log.error("Unexpected TCP frame handler failure: remote={}, messageId={}, " +
                            "vehicleId={}, activeClients={}, connectionFailures={}",
                        client.getRemoteSocketAddress(), message.messageId(), message.vehicleId(),
                        clients.size(), metrics.connectionFailures(), exception);
                    break;
                }
            }
        } catch (FrameStreamClosedException exception) {
            log.debug(
                "TCP client closed the connection: remote={}, activeClients={}, receivedFrames={}",
                client.getRemoteSocketAddress(), clients.size(), metrics.receivedFrames());

        } catch (SocketTimeoutException exception) {
            metrics.connectionTimedOut();
            log.debug("TCP client read timed out: remote={}, readTimeout={}, activeClients={}, " +
                    "connectionTimeouts={}", client.getRemoteSocketAddress(),
                properties.readTimeout(),
                clients.size(), metrics.connectionTimeouts());

        } catch (IOException exception) {
            if (stopping.get()) {
                log.debug("TCP client closed during server shutdown: remote={}, activeClients={}",
                    client.getRemoteSocketAddress(), clients.size());
            } else {
                metrics.connectionFailed();

                log.warn("TCP client connection failed: remote={}, activeClients={}, " +
                        "connectionFailures={}", client.getRemoteSocketAddress(), clients.size(),
                    metrics.connectionFailures(), exception);
            }
        } finally {
            clients.remove(client);
            connectionPermits.release();
            log.debug(
                "TCP client disconnected: remote={}, activeClients={}, acceptedConnections={}, " +
                    "rejectedConnections={}, receivedFrames={}, connectionFailures={}",
                client.getRemoteSocketAddress(), clients.size(), metrics.acceptedConnections(),
                metrics.rejectedConnections(), metrics.receivedFrames(),
                metrics.connectionFailures());
        }
    }

    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        log.info("Stopping TCP server: activeClients={}, acceptedConnections={}, " +
                "rejectedConnections={}, receivedFrames={}, connectionFailures={}", clients.size(),
            metrics.acceptedConnections(), metrics.rejectedConnections(), metrics.receivedFrames(),
            metrics.connectionFailures());
        closeServerSocket();
        executor.shutdown();
        if (!awaitGracefulTermination()) {
            log.warn("TCP graceful shutdown timed out; closing {} active client(s)",
                clients.size());
            closeClients();
            awaitForcedTermination();
        }
        log.info("TCP server stopped: activeClients={}, acceptedConnections={}, " +
                "rejectedConnections={}, receivedFrames={}, connectionFailures={}", clients.size(),
            metrics.acceptedConnections(), metrics.rejectedConnections(), metrics.receivedFrames(),
            metrics.connectionFailures());
    }

    private void closeServerSocket() {
        if (serverSocket == null || serverSocket.isClosed()) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException exception) {
            log.debug("Unable to close TCP server socket during shutdown", exception);
        }
    }

    private void closeClients() {
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException exception) {
                log.debug("Unable to close TCP client during shutdown: remote={}",
                    client.getRemoteSocketAddress(), exception);
            }
        }
    }

    private void closeRejectedClient(Socket client) {
        try {
            client.close();
        } catch (IOException exception) {
            log.debug("Unable to close rejected TCP client: remote={}",
                client.getRemoteSocketAddress(), exception);
        }
    }

    private void awaitForcedTermination() {
        try {
            if (!executor.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("TCP executor did not terminate after closing client sockets");

                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private boolean awaitGracefulTermination() {
        try {
            return executor.awaitTermination(properties.shutdownGracePeriod().toMillis(),
                TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            log.warn("Interrupted while waiting for TCP graceful shutdown");

            return false;
        }
    }
}
