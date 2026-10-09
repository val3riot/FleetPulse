package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.FrameStreamClosedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import java.util.Map;
import java.util.UUID;

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
        log.atInfo().addKeyValue("event.action", "tcp.server.listening")
                .addKeyValue("port", serverSocket.getLocalPort())
                .addKeyValue("activeClients", clients.size())
                .log("TCP server listening: port={}, activeClients={}",
                        serverSocket.getLocalPort(),
                        clients.size());
        try {
            while (!serverSocket.isClosed()) {
                acceptClient();
            }
        } catch (SocketException exception) {
            if (serverSocket.isClosed()) {
                log.atDebug().addKeyValue("event.action",
                        "tcp.accept.loop.stopped.because.the.server.socket.was.closed")
                        .log("TCP accept loop stopped because the server socket was closed");
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
            log.atWarn().addKeyValue("event.action",
                    "tcp.connection.rejected.because.capacity.is.exhausted")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("activeClients", clients.size())
                    .addKeyValue("maxConnections", properties.maxConnections())
                    .addKeyValue("capacityRejectedConnections",
                            metrics.capacityRejectedConnections())
                    .log("TCP connection rejected because capacity is exhausted: remote={}, " +
                            "activeClients={}, maxConnections={}, capacityRejectedConnections={}",
                            client.getRemoteSocketAddress(),
                            clients.size(),
                            properties.maxConnections(),
                            metrics.capacityRejectedConnections());
            return;
        }
        try {
            client.setSoTimeout(Math.toIntExact(properties.readTimeout().toMillis()));
        } catch (SocketException exception) {
            connectionPermits.release();
            metrics.connectionFailed();
            closeRejectedClient(client);
            log.atWarn().addKeyValue("event.action", "unable.to.configure.tcp.client.read.timeout")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("readTimeout", properties.readTimeout())
                    .addKeyValue("connectionFailures", metrics.connectionFailures())
                    .addKeyValue("errorType", exception.getClass().getSimpleName())
                    .log("Unable to configure TCP client read timeout: remote={}, readTimeout={}, "
                            +
                            "connectionFailures={}", client.getRemoteSocketAddress(),
                            properties.readTimeout(),
                            metrics.connectionFailures());
            return;
        }
        clients.add(client);
        try {
            executor.submit(() -> handleClient(client));
            metrics.connectionAccepted();
            log.atDebug().addKeyValue("event.action", "tcp.client.accepted")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("activeClients", clients.size())
                    .log("TCP client accepted: remote={}, activeClients={}",
                            client.getRemoteSocketAddress(),
                            clients.size());
        } catch (RejectedExecutionException exception) {
            clients.remove(client);
            connectionPermits.release();
            metrics.connectionRejected();
            closeRejectedClient(client);
            log.atDebug().addKeyValue("event.action", "tcp.client.rejected.during.shutdown")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("activeClients", clients.size())
                    .addKeyValue("rejectedConnections", metrics.rejectedConnections())
                    .log("TCP client rejected during shutdown: remote={}, activeClients={}, " +
                            "rejectedConnections={}", client.getRemoteSocketAddress(),
                            clients.size(),
                            metrics.rejectedConnections());
        }
    }

    int activeClients() {
        return clients.size();
    }

    private void handleClient(Socket client) {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        MDC.put("connectionId", UUID.randomUUID().toString());
        try (client) {
            InputStream inputStream = client.getInputStream();
            OutputStream outputStream = client.getOutputStream();
            while (!Thread.currentThread().isInterrupted()) {
                TelemetryMessage message = frameDecoder.read(inputStream);
                metrics.frameReceived();
                log.atDebug().addKeyValue("event.action", "tcp.frame.received")
                        .addKeyValue("remote", client.getRemoteSocketAddress())
                        .addKeyValue("messageId", message.messageId())
                        .addKeyValue("vehicleId", message.vehicleId())
                        .addKeyValue("activeClients", clients.size())
                        .addKeyValue("receivedFrames", metrics.receivedFrames())
                        .log("TCP frame received: remote={}, messageId={}, vehicleId={}," +
                                " activeClients={}," +
                                " receivedFrames={}", client.getRemoteSocketAddress(),
                                message.messageId(),
                                message.vehicleId(),
                                clients.size(),
                                metrics.receivedFrames());
                try {
                    TelemetryAck ack = frameHandler.handle(message);
                    acknowledgementEncoder.write(ack, outputStream);
                } catch (RuntimeException exception) {
                    metrics.connectionFailed();
                    log.atError().addKeyValue("event.action",
                            "unexpected.tcp.frame.handler.failure")
                            .addKeyValue("remote", client.getRemoteSocketAddress())
                            .addKeyValue("messageId", message.messageId())
                            .addKeyValue("vehicleId", message.vehicleId())
                            .addKeyValue("activeClients", clients.size())
                            .addKeyValue("connectionFailures", metrics.connectionFailures())
                            .addKeyValue("errorType", exception.getClass().getSimpleName())
                            .log("Unexpected TCP frame handler failure: remote={}, messageId={}, " +
                                    "vehicleId={}, activeClients={}, connectionFailures={}",
                                    client.getRemoteSocketAddress(),
                                    message.messageId(),
                                    message.vehicleId(),
                                    clients.size(),
                                    metrics.connectionFailures());
                    break;
                }
            }
        } catch (FrameStreamClosedException exception) {
            log.atDebug().addKeyValue("event.action", "tcp.client.closed.the.connection")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("activeClients", clients.size())
                    .addKeyValue("receivedFrames", metrics.receivedFrames())
                    .log("TCP client closed the connection: remote={}, activeClients={}," +
                            " receivedFrames={}",

                            client.getRemoteSocketAddress(),
                            clients.size(),
                            metrics.receivedFrames());

        } catch (SocketTimeoutException exception) {
            metrics.connectionTimedOut();
            log.atDebug().addKeyValue("event.action", "tcp.client.read.timed.out")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("readTimeout", properties.readTimeout())
                    .addKeyValue("activeClients", clients.size())
                    .addKeyValue("connectionTimeouts", metrics.connectionTimeouts())
                    .log("TCP client read timed out: remote={}, readTimeout={}, activeClients={}, "
                            +
                            "connectionTimeouts={}", client.getRemoteSocketAddress(),
                            properties.readTimeout(),
                            clients.size(),
                            metrics.connectionTimeouts());

        } catch (IOException exception) {
            if (stopping.get()) {
                log.atDebug().addKeyValue("event.action",
                        "tcp.client.closed.during.server.shutdown")
                        .addKeyValue("remote", client.getRemoteSocketAddress())
                        .addKeyValue("activeClients", clients.size())
                        .log("TCP client closed during server shutdown: remote={}, activeClients={}",
                                client.getRemoteSocketAddress(),
                                clients.size());
            } else {
                metrics.recordFrameRejectionIfApplicable(exception);
                metrics.connectionFailed();

                log.atWarn().addKeyValue("event.action", "tcp.client.connection.failed")
                        .addKeyValue("remote", client.getRemoteSocketAddress())
                        .addKeyValue("activeClients", clients.size())
                        .addKeyValue("connectionFailures", metrics.connectionFailures())
                        .addKeyValue("errorType", exception.getClass().getSimpleName())
                        .log("TCP client connection failed: remote={}, activeClients={}, " +
                                "connectionFailures={}", client.getRemoteSocketAddress(),
                                clients.size(),
                                metrics.connectionFailures());
            }
        } finally {
            clients.remove(client);
            connectionPermits.release();
            log.atDebug().addKeyValue("event.action", "tcp.client.disconnected")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("activeClients", clients.size())
                    .addKeyValue("acceptedConnections", metrics.acceptedConnections())
                    .addKeyValue("rejectedConnections", metrics.rejectedConnections())
                    .addKeyValue("receivedFrames", metrics.receivedFrames())
                    .addKeyValue("connectionFailures", metrics.connectionFailures())
                    .log("TCP client disconnected: remote={}, activeClients={}," +
                            " acceptedConnections={}, " +
                            "rejectedConnections={}, receivedFrames={}, connectionFailures={}",
                            client.getRemoteSocketAddress(),
                            clients.size(),
                            metrics.acceptedConnections(),
                            metrics.rejectedConnections(),
                            metrics.receivedFrames(),
                            metrics.connectionFailures());
            if (previous == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(previous);
            }
        }
    }

    @Override
    public void close() {
        if (!stopping.compareAndSet(false, true)) {
            return;
        }
        log.atInfo().addKeyValue("event.action", "stopping.tcp.server")
                .addKeyValue("activeClients", clients.size())
                .addKeyValue("acceptedConnections", metrics.acceptedConnections())
                .addKeyValue("rejectedConnections", metrics.rejectedConnections())
                .addKeyValue("receivedFrames", metrics.receivedFrames())
                .addKeyValue("connectionFailures", metrics.connectionFailures())
                .log("Stopping TCP server: activeClients={}, acceptedConnections={}, " +
                        "rejectedConnections={}, receivedFrames={}, connectionFailures={}",
                        clients.size(),
                        metrics.acceptedConnections(),
                        metrics.rejectedConnections(),
                        metrics.receivedFrames(),
                        metrics.connectionFailures());
        closeServerSocket();
        executor.shutdown();
        if (!awaitGracefulTermination()) {
            log.atWarn().addKeyValue("event.action", "tcp.graceful.shutdown.timed.out.closing")
                    .log("TCP graceful shutdown timed out; closing {} active client(s)",
                            clients.size());
            closeClients();
            awaitForcedTermination();
        }
        log.atInfo().addKeyValue("event.action", "tcp.server.stopped")
                .addKeyValue("activeClients", clients.size())
                .addKeyValue("acceptedConnections", metrics.acceptedConnections())
                .addKeyValue("rejectedConnections", metrics.rejectedConnections())
                .addKeyValue("receivedFrames", metrics.receivedFrames())
                .addKeyValue("connectionFailures", metrics.connectionFailures())
                .log("TCP server stopped: activeClients={}, acceptedConnections={}, " +
                        "rejectedConnections={}, receivedFrames={}, connectionFailures={}",
                        clients.size(),
                        metrics.acceptedConnections(),
                        metrics.rejectedConnections(),
                        metrics.receivedFrames(),
                        metrics.connectionFailures());
    }

    private void closeServerSocket() {
        if (serverSocket == null || serverSocket.isClosed()) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException exception) {
            log.atDebug().addKeyValue("event.action",
                    "unable.to.close.tcp.server.socket.during.shutdown")
                    .addKeyValue("errorType", exception.getClass().getSimpleName())
                    .log("Unable to close TCP server socket during shutdown");
        }
    }

    private void closeClients() {
        for (Socket client : clients) {
            try {
                client.close();
            } catch (IOException exception) {
                log.atDebug().addKeyValue("event.action",
                        "unable.to.close.tcp.client.during.shutdown")
                        .addKeyValue("remote", client.getRemoteSocketAddress())
                        .addKeyValue("errorType", exception.getClass().getSimpleName())
                        .log("Unable to close TCP client during shutdown: remote={}",
                                client.getRemoteSocketAddress());
            }
        }
    }

    private void closeRejectedClient(Socket client) {
        try {
            client.close();
        } catch (IOException exception) {
            log.atDebug().addKeyValue("event.action", "unable.to.close.rejected.tcp.client")
                    .addKeyValue("remote", client.getRemoteSocketAddress())
                    .addKeyValue("errorType", exception.getClass().getSimpleName())
                    .log("Unable to close rejected TCP client: remote={}",
                            client.getRemoteSocketAddress());
        }
    }

    private void awaitForcedTermination() {
        try {
            if (!executor.awaitTermination(FORCE_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.atWarn().addKeyValue("event.action",
                        "tcp.executor.did.not.terminate.after.closing.client.sockets")
                        .log("TCP executor did not terminate after closing client sockets");

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

            log.atWarn().addKeyValue("event.action",
                    "interrupted.while.waiting.for.tcp.graceful.shutdown")
                    .log("Interrupted while waiting for TCP graceful shutdown");

            return false;
        }
    }
}
