package it.fleetpulse.processor.telemetry.alert;

import java.util.Objects;
import java.util.Optional;

@FunctionalInterface
public interface AlertRule {

    Optional<AlertCandidate> evaluate(AlertVehicle vehicle, AlertTelemetrySample sample);

    static void requireMatchingVehicle(AlertVehicle vehicle, AlertTelemetrySample sample) {
        Objects.requireNonNull(vehicle, "vehicle must not be null");
        Objects.requireNonNull(sample, "sample must not be null");
        if (!vehicle.id().equals(sample.vehicleId())) {
            throw new IllegalArgumentException("sample vehicleId must match vehicle id");
        }
    }
}
