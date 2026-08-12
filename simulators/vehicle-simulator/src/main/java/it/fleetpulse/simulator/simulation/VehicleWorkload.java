package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.simulation.reconnect.RetrySleeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

public final class VehicleWorkload implements VehicleTask {

    private static final Logger log = LoggerFactory.getLogger(VehicleWorkload.class);

    private final VehicleConnection connection;
    private final TelemetryProfile telemetryProfile;
    private final Duration sendInterval;
    private final RetrySleeper sleeper;
    private SimulatedVehicleState state;

    public VehicleWorkload(
            SimulatedVehicleState initialState,
            VehicleConnection connection,
            TelemetryProfile telemetryProfile,
            Duration sendInterval
    ) {
        this(initialState, connection, telemetryProfile, sendInterval, Thread::sleep);
    }

    VehicleWorkload(
            SimulatedVehicleState initialState,
            VehicleConnection connection,
            TelemetryProfile telemetryProfile,
            Duration sendInterval,
            RetrySleeper sleeper
    ) {
        this.state = Objects.requireNonNull(initialState, "initialState");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.telemetryProfile = Objects.requireNonNull(telemetryProfile, "telemetryProfile");
        if (sendInterval == null || sendInterval.isZero() || sendInterval.isNegative()) {
            throw new IllegalArgumentException("sendInterval must be greater than zero");
        }
        this.sendInterval = sendInterval;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    @Override
    public void run() {
        log.info("Starting telemetry workload for vehicle {}", state.externalCode());
        try {
            connection.connect();
            while (!Thread.currentThread().isInterrupted()) {
                if (!connection.isConnected()) {
                    connection.connect();
                }
                TelemetrySample sample = telemetryProfile.next(state);
                try {
                    connection.send(sample.message());
                    state = sample.nextState();
                    sleeper.sleep(sendInterval);
                } catch (IOException sendFailure) {
                    log.debug(
                            "Vehicle {} telemetry send failed; connection will be restored before the next sample",
                            state.externalCode(),
                            sendFailure
                    );
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException connectionStopped) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error(
                        "Vehicle {} telemetry workload stopped after a connection error",
                        state.externalCode(),
                        connectionStopped
                );
            }
        } catch (RuntimeException unexpected) {
            log.error("Vehicle {} telemetry workload failed", state.externalCode(), unexpected);
        } finally {
            connection.close();
            log.info("Stopped telemetry workload for vehicle {}", state.externalCode());
        }
    }

    @Override
    public void close() {
        connection.close();
    }
}
