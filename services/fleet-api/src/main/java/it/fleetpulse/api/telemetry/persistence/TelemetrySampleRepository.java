package it.fleetpulse.api.telemetry.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository read-only dello storico telemetrico.
 */
public interface TelemetrySampleRepository extends Repository<TelemetrySampleEntity, Long> {

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
