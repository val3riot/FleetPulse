package it.fleetpulse.processor.telemetry.alert;

import java.util.Objects;
import java.util.UUID;

public record AlertTelemetrySample(
        UUID messageId,
        UUID vehicleId,
        double engineTemperatureC,
        double batteryVoltage,
        long odometerKm
) {

    public AlertTelemetrySample {
        Objects.requireNonNull(messageId, "messageId must not be null");
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        requireFinite(engineTemperatureC, "engineTemperatureC");
        requireFinite(batteryVoltage, "batteryVoltage");
        if (batteryVoltage < 0) {
            throw new IllegalArgumentException("batteryVoltage must not be negative");
        }
        if (odometerKm < 0) {
            throw new IllegalArgumentException("odometerKm must not be negative");
        }
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
