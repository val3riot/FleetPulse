package it.fleetpulse.simulator.simulation.reconnect;

import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.simulator.config.ReconnectProperties;
import it.fleetpulse.simulator.simulation.VehicleConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public final class ReconnectingVehicleConnection implements VehicleConnection {

    private static final Logger log = LoggerFactory.getLogger(ReconnectingVehicleConnection.class);

    private final String vehicleCode;
    private final VehicleConnection delegate;
    private final ExponentialBackoff backoff;
    private final RetrySleeper sleeper;
    private final int maxAttempts;
    private final double jitterRatio;
    private final Duration maximumDelay;

    public ReconnectingVehicleConnection(String vehicleCode, VehicleConnection delegate,
        ReconnectProperties properties) {
        this(vehicleCode, delegate, properties, Thread::sleep);
    }

    ReconnectingVehicleConnection(String vehicleCode, VehicleConnection delegate,
        ReconnectProperties properties, RetrySleeper sleeper) {
        if (vehicleCode == null || vehicleCode.isBlank()) {
            throw new IllegalArgumentException("vehicleCode must not be blank");
        }
        this.vehicleCode = vehicleCode;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(properties, "properties");
        this.backoff = new ExponentialBackoff(properties.initialBackoff(), properties.maxBackoff());
        this.maxAttempts = properties.maxAttempts();
        this.jitterRatio = properties.jitterRatio();
        this.maximumDelay = properties.maxBackoff();
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public void connect() throws IOException {
        if (delegate.isConnected()) {
            return;
        }

        for (int attempt = 1; attempt <= maxAttempts && !delegate.isConnected(); attempt++) {
            try {
                delegate.connect();
                if (!delegate.isConnected()) {
                    throw new IOException(
                        "Connection attempt completed without an open connection");
                }
                backoff.reset();
                log.info("Vehicle {} connected to telemetry gateway", vehicleCode);
            } catch (IOException connectionFailure) {
                delegate.close();
                if (attempt == maxAttempts) {
                    log.error("Vehicle {} exhausted {} gateway connection attempts", vehicleCode,
                        maxAttempts);
                    throw connectionFailure;
                }
                Duration delay = withJitter(backoff.nextDelay());
                log.warn("Vehicle {} gateway connection attempt {} failed; retrying in {} ms: {}",
                    vehicleCode, attempt, delay.toMillis(), connectionFailure.getMessage());
                awaitRetry(delay, connectionFailure);
            }
        }
    }

    @Override
    public void send(TelemetryMessage message) throws IOException {
        if (!delegate.isConnected()) {
            connect();
        }
        try {
            delegate.send(message);
        } catch (IOException sendFailure) {
            delegate.close();
            log.warn("Vehicle {} lost its gateway connection while sending telemetry: {}",
                vehicleCode, sendFailure.getMessage());
            throw sendFailure;
        }
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public void close() {
        delegate.close();
    }

    private void awaitRetry(Duration delay,
        IOException connectionFailure) throws InterruptedIOException {
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            InterruptedIOException stopped = new InterruptedIOException(
                "Interrupted while waiting to reconnect vehicle " + vehicleCode);
            stopped.initCause(connectionFailure);
            stopped.addSuppressed(interrupted);
            throw stopped;
        }
    }

    private Duration withJitter(Duration delay) {
        long delayMillis = delay.toMillis();
        if (jitterRatio == 0 || delayMillis == 0) {
            return delay;
        }
        double multiplier = 1 + ThreadLocalRandom.current().nextDouble(-jitterRatio, jitterRatio);
        long jitteredMillis = Math.max(1, Math.round(delayMillis * multiplier));
        return Duration.ofMillis(Math.min(jitteredMillis, maximumDelay.toMillis()));
    }
}
