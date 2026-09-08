package it.fleetpulse.api.state;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.vehicle.VehicleRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class VehicleStateService {
    private final VehicleRepository vehicles;
    private final LatestStateQuery cache;
    private final LatestSampleQuery samples;
    private final LatestStateProjection repair;
    private final VehicleStateProperties properties;
    private final Clock clock;
    private final VehicleStateObservability observations;

    public VehicleStateService(VehicleRepository vehicles, LatestStateQuery cache,
        LatestSampleQuery samples, LatestStateProjection repair, VehicleStateProperties properties,
        Clock clock, VehicleStateObservability observations) {
        this.vehicles = vehicles;
        this.cache = cache;
        this.samples = samples;
        this.repair = repair;
        this.properties = properties;
        this.clock = clock;
        this.observations = observations;
    }

    // Nessuna transazione PostgreSQL deve restare aperta durante le operazioni Redis.
    public VehicleStateResponse findByVehicleId(UUID vehicleId) {
        Optional<LatestVehicleState> cached = Optional.empty();
        try {
            cached = cache.findByVehicleId(vehicleId);
            if (cached.isPresent()) {
                observations.hit();
            } else {
                observations.miss();
            }
        } catch (LatestStateProjectionException failure) {
            observations.failure(false, failure);
        }

        LatestVehicleState state = cached.orElseGet(() -> fallback(vehicleId));
        boolean stale = Duration.between(state.lastSeenAt(), clock.instant())
            .compareTo(properties.staleAfter()) > 0;
        return VehicleStateResponse.from(state, stale);
    }

    private LatestVehicleState fallback(UUID vehicleId) {
        observations.fallback();
        if (!vehicles.existsById(vehicleId)) {
            throw new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND);
        }
        LatestVehicleState state = samples.findByVehicleId(vehicleId)
            .orElseThrow(() -> new ApplicationException(ErrorCode.VEHICLE_STATE_NOT_AVAILABLE));
        try {
            repair.updateIfNewer(state);
        } catch (LatestStateProjectionException failure) {
            observations.failure(true, failure);
        }
        return state;
    }
}
