package it.fleetpulse.api.state.persistence;

import it.fleetpulse.api.state.LatestSampleQuery;
import it.fleetpulse.api.state.LatestVehicleState;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgreSqlLatestSampleQuery implements LatestSampleQuery {
    private final TelemetrySampleRepository samples;

    public PostgreSqlLatestSampleQuery(TelemetrySampleRepository samples) {
        this.samples = samples;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LatestVehicleState> findByVehicleId(UUID vehicleId) {
        return samples.findLatestState(vehicleId, PageRequest.of(0, 1)).stream().findFirst();
    }
}
