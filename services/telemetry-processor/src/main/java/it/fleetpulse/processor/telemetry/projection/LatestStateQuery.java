package it.fleetpulse.processor.telemetry.projection;

import java.util.Optional;
import java.util.UUID;

public interface LatestStateQuery {
    Optional<LatestVehicleState> findByVehicleId(UUID vehicleId);
}
