package it.fleetpulse.processor.telemetry.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MaintenanceAlertRepository
    extends JpaRepository<MaintenanceAlertEntity, UUID> {
}
