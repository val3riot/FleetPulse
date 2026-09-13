package it.fleetpulse.api.telemetry.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository read-only dello storico telemetrico.
 */
public interface TelemetrySampleRepository extends Repository<TelemetrySampleEntity, Long> {

    /**
     * Cerca i sample del veicolo nell'intervallo UTC inclusivo richiesto.
     */
    Page<TelemetrySampleEntity> findAllByVehicleIdAndObservedAtBetween(UUID vehicleId,
        Instant from, Instant to, Pageable pageable);
}
