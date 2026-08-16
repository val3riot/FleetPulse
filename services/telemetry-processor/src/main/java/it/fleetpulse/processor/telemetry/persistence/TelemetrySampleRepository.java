package it.fleetpulse.processor.telemetry.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetrySampleRepository extends JpaRepository<TelemetrySampleEntity, Long> {
}
