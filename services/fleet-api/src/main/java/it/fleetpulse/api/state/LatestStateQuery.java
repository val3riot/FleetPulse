package it.fleetpulse.api.state;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface LatestStateQuery {
    Optional<LatestVehicleState> findByVehicleId(UUID vehicleId);
}
