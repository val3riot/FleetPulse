package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.FrameStreamClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public final class TcpServer implements AutoCloseable {
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;
    private static final Logger log = LoggerFactory.getLogger(TcpServer.class);
    private volatile ServerSocket serverSocket;
    private final ExecutorService executor;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final FrameDecoder frameDecoder;
    private final FrameHandler frameHandler;
    private final TcpServerProperties properties;
    private final TcpServerMetrics metrics;
    private final Semaphore connectionPermits;

    public TcpServer(FrameHandler frameHandler, TcpServerProperties properties, FrameDecoder frameDecoder, MeterRegistry meterRegistry) {
        this(frameHandler, properties, frameDecoder, Executors.newVirtualThreadPerTaskExecutor(), meterRegistry);
    }

    TcpServer(FrameHandler frameHandler, TcpServerProperties properties, FrameDecoder frameDecoder, ExecutorService executor, MeterRegistry meterRegistry) {
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler must not be null");
        this.frameDecoder = Objects.requireNonNull(frameDecoder, "frameDecoder must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.metrics = new TcpServerMetrics(Objects.requireNonNull(meterRegistry, "meterRegistry must not be null"), clients);
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
        log.info("TCP server listening: port={}, activeClients={}", serverSocket.getLocalPort(), clients.size());
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
            log.warn("TCP connection rejected because capacity is exhausted: remote={}, activeClients={}, maxConnections={}, capacityRejectedConnections={}", client.getRemoteSocketAddress(), clients.size(), properties.maxConnections(), metrics.capacityRejectedConnections());
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
            metrics.connectionRejected();
            connectionPermits.release();
            closeRejectedClient(client);
            log.debug(
                    "TCP client rejected during shutdown: remote={}, activeClients={}, "
                            + "rejectedConnections={}",
                    client.getRemoteSocketAddress(), clients.size(), metrics.rejectedConnections()
            );
        }
    }

    int activeClients() {
        return clients.size();
    }

    private void handleClient(Socket client) {
        try (client) {
            InputStream inputStream = client.getInputStream();
            while (!Thread.currentThread().isInterrupted()) {
                TelemetryMessage message = frameDecoder.read(inputStream);
                metrics.frameReceived();
                log.debug(
                        "TCP frame received: remote={}, messageId={}, vehicleId={}, "
                                + "activeClients={}, receivedFrames={}",
                        client.getRemoteSocketAddress(), message.messageId(), message.vehicleId(),
                        clients.size(), metrics.receivedFrames()
                );
                try {
                    frameHandler.handle(message);
                } catch (RuntimeException exception) {
                    metrics.connectionFailed();
                    log.error(
                            "Unexpected TCP frame handler failure: remote={}, messageId={}, "
                                    + "vehicleId={}, activeClients={}, connectionFailures={}",
                            client.getRemoteSocketAddress(), message.messageId(), message.vehicleId(),
                            clients.size(), metrics.connectionFailures(), exception
                    );
                    break;
                }
            }
        } catch (FrameStreamClosedException exception) {
            log.debug("TCP client closed the connection: remote={}, activeClients={}, receivedFrames={}",
                    client.getRemoteSocketAddress(), clients.size(), metrics.receivedFrames());
        } catch (IOException exception) {
            if (executor.isShutdown()) {
                log.debug("TCP client closed during server shutdown: remote={}, activeClients={}",
                        client.getRemoteSocketAddress(), clients.size());
            } else {
                metrics.connectionFailed();
                log.warn(
                        "TCP client connection failed: remote={}, activeClients={}, connectionFailures={}",
                        client.getRemoteSocketAddress(), clients.size(), metrics.connectionFailures(), exception
                );
            }
        } finally {
            clients.remove(client);
            connectionPermits.release();
            log.debug(
                    "TCP client disconnected: remote={}, activeClients={}, acceptedConnections={}, "
                            + "rejectedConnections={}, receivedFrames={}, connectionFailures={}",
                    client.getRemoteSocketAddress(), clients.size(), metrics.acceptedConnections(),
                    metrics.rejectedConnections(), metrics.receivedFrames(), metrics.connectionFailures()
            );
        }
    }

    @Override
    public void close() {
        log.info(
                "Stopping TCP server: activeClients={}, acceptedConnections={}, "
                        + "rejectedConnections={}, receivedFrames={}, connectionFailures={}",
                clients.size(), metrics.acceptedConnections(), metrics.rejectedConnections(),
                metrics.receivedFrames(), metrics.connectionFailures()
        );
        closeServerSocket();
        executor.shutdown();
        closeClients();
        awaitExecutorTermination();
        log.info(
                "TCP server stopped: activeClients={}, acceptedConnections={}, "
                        + "rejectedConnections={}, receivedFrames={}, connectionFailures={}",
                clients.size(), metrics.acceptedConnections(), metrics.rejectedConnections(),
                metrics.receivedFrames(), metrics.connectionFailures()
        );
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
                log.debug("Unable to close TCP client during shutdown: remote={}", client.getRemoteSocketAddress(), exception);
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

    private void awaitExecutorTermination() {
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("TCP executor shutdown timed out: activeClients={}", clients.size());
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("TCP executor shutdown interrupted: activeClients={}", clients.size());
            executor.shutdownNow();
        }
    }
}
