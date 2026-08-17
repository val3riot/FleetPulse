package it.fleetpulse.gateway.tcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.SmartLifecycle;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class TcpServerLifecycle implements SmartLifecycle {

    private static final long LISTENER_JOIN_TIMEOUT_MILLIS = 5_000;
    private static final Logger log = LoggerFactory.getLogger(TcpServerLifecycle.class);
    private final TcpServer tcpServer;
    private volatile boolean running;
    private Thread serverThread;

    public TcpServerLifecycle(TcpServer tcpServer) {
        this.tcpServer = Objects.requireNonNull(tcpServer, "tcpServer must not be null");
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        CompletableFuture<Integer> bindResult = new CompletableFuture<>();
        running = true;
        serverThread = Thread.ofPlatform().name("fleetpulse-tcp-listener").start(() -> {
            try {
                tcpServer.start(bindResult);
            } catch (IOException exception) {
                if (!bindResult.isDone()) {
                    bindResult.completeExceptionally(exception);
                } else if (!bindResult.isCompletedExceptionally()) {
                    log.error("TCP server terminated unexpectedly", exception);
                }
            } finally {
                running = false;
            }
        });
        try {
            int boundPort = bindResult.join();
            log.info("TCP listener lifecycle started: port={}", boundPort);
        } catch (CompletionException exception) {
            running = false;
            tcpServer.close();
            joinServerThread();
            throw new ApplicationContextException("Unable to bind FleetPulse TCP listener",
                exception.getCause());
        }
    }

    @Override
    public void stop() {
        tcpServer.close();

        joinServerThread();

        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void joinServerThread() {
        if (serverThread == null || serverThread == Thread.currentThread()) {
            return;
        }
        try {
            serverThread.join(LISTENER_JOIN_TIMEOUT_MILLIS);
            if (serverThread.isAlive()) {
                log.warn("TCP listener thread did not stop within {} ms",
                    LISTENER_JOIN_TIMEOUT_MILLIS);
                serverThread.interrupt();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for TCP listener thread to stop");
        }
    }
}
