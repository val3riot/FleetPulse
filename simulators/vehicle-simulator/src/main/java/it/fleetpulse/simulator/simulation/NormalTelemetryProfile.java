package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

public final class NormalTelemetryProfile implements TelemetryProfile {

    private static final double MIN_SPEED_KMH = 0.0;
    private static final double MAX_SPEED_KMH = 130.0;
    private static final double MIN_ENGINE_TEMPERATURE_C = 75.0;
    private static final double MAX_ENGINE_TEMPERATURE_C = 105.0;
    private static final double MIN_BATTERY_VOLTAGE = 12.0;
    private static final double MAX_BATTERY_VOLTAGE = 14.5;
    private static final double MAX_COORDINATE_DELTA = 0.0001;

    private final Duration sendInterval;
    private final Clock clock;
    private final RandomGenerator random;
    private final MessageIdGenerator messageIdGenerator;

    public NormalTelemetryProfile(Duration sendInterval, Clock clock, RandomGenerator random,
        MessageIdGenerator messageIdGenerator) {
        if (sendInterval == null || sendInterval.isZero() || sendInterval.isNegative()) {
            throw new IllegalArgumentException("sendInterval must be greater than zero");
        }
        this.sendInterval = sendInterval;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.messageIdGenerator =
            Objects.requireNonNull(messageIdGenerator, "messageIdGenerator must not be null");
    }

    @Override
    public TelemetrySample next(SimulatedVehicleState currentState) {
        Objects.requireNonNull(currentState, "currentState must not be null");

        double speedKmh = clamp(currentState.speedKmh() + delta(5.0), MIN_SPEED_KMH, MAX_SPEED_KMH);
        double engineTemperatureC =
            clamp(currentState.engineTemperatureC() + delta(1.0), MIN_ENGINE_TEMPERATURE_C,
                MAX_ENGINE_TEMPERATURE_C);
        double batteryVoltage =
            clamp(currentState.batteryVoltage() + delta(0.05), MIN_BATTERY_VOLTAGE,
                MAX_BATTERY_VOLTAGE);
        double elapsedHours =
            sendInterval.getSeconds() / 3_600.0 + sendInterval.getNano() / 3_600_000_000_000.0;
        double odometerKm = currentState.odometerKm() + speedKmh * elapsedHours;
        double latitude = clamp(currentState.latitude() + delta(MAX_COORDINATE_DELTA), -90.0, 90.0);
        double longitude =
            clamp(currentState.longitude() + delta(MAX_COORDINATE_DELTA), -180.0, 180.0);

        UUID messageId =
            Objects.requireNonNull(messageIdGenerator.next(), "messageIdGenerator returned null");
        Instant observedAt = clock.instant();
        TelemetryMessage message =
            new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION, messageId,
                currentState.vehicleId(), currentState.sequenceNumber(), observedAt, speedKmh,
                engineTemperatureC, batteryVoltage, (long) Math.floor(odometerKm), latitude,
                longitude);
        SimulatedVehicleState nextState =
            currentState.next(speedKmh, engineTemperatureC, batteryVoltage, odometerKm, latitude,
                longitude);
        return new TelemetrySample(nextState, message);
    }

    private double delta(double maximumMagnitude) {
        return random.nextDouble(-maximumMagnitude, maximumMagnitude);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
