package it.fleetpulse.api.alert;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface MaintenanceAlertRepository
    extends JpaRepository<MaintenanceAlertEntity, UUID>,
    JpaSpecificationExecutor<MaintenanceAlertEntity> {
}
