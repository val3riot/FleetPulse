package it.fleetpulse.processor.telemetry.vehicle;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface VehicleRegistry {

    Optional<VehicleStatus> findStatus(UUID vehicleId);
}
