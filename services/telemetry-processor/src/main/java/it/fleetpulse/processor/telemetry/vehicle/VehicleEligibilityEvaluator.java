package it.fleetpulse.processor.telemetry.vehicle;

import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public final class VehicleEligibilityEvaluator {

    private final VehicleRegistry registry;

    public VehicleEligibilityEvaluator(VehicleRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    public Optional<TelemetryRejectionReason> rejectionReason(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");

        Optional<VehicleStatus> status = registry.findStatus(vehicleId);

        if (status.isEmpty()) {
            return Optional.of(TelemetryRejectionReason.UNKNOWN_VEHICLE);
        }

        if (status.get() == VehicleStatus.DISABLED) {
            return Optional.of(TelemetryRejectionReason.VEHICLE_DISABLED);
        }

        return Optional.empty();
    }
}
