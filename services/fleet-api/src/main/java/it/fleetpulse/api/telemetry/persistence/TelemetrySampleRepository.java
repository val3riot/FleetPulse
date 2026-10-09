package it.fleetpulse.api.telemetry.persistence;

import it.fleetpulse.api.state.LatestVehicleState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository read-only dello storico telemetrico e delle projection di stato.
 */
public interface TelemetrySampleRepository extends Repository<TelemetrySampleEntity, Long> {

    @Query("""
            SELECT new it.fleetpulse.api.state.LatestVehicleState(
                sample.vehicleId, sample.sequenceNumber, sample.observedAt, sample.speedKmh,
                sample.engineTemperatureC, sample.batteryVoltage, sample.odometerKm,
                sample.latitude, sample.longitude)
            FROM TelemetrySampleEntity sample
            WHERE sample.vehicleId = :vehicleId
            ORDER BY sample.observedAt DESC, sample.sequenceNumber DESC, sample.id DESC
            """)
    List<LatestVehicleState> findLatestState(@Param("vehicleId") UUID vehicleId, Pageable pageable);

    @Query("""
            SELECT COUNT(DISTINCT sample.vehicleId)
            FROM TelemetrySampleEntity sample
            WHERE sample.observedAt >= :from AND sample.observedAt <= :to
            """)
    long countDistinctReportingVehicles(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Cerca i sample del veicolo nell'intervallo UTC inclusivo richiesto.
     */
    Page<TelemetrySampleEntity> findAllByVehicleIdAndObservedAtBetween(UUID vehicleId,
            Instant from, Instant to, Pageable pageable);
}
