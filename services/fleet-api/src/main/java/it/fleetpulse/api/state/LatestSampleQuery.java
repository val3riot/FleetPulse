package it.fleetpulse.api.state;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface LatestSampleQuery {
    Optional<LatestVehicleState> findByVehicleId(UUID vehicleId);
}
