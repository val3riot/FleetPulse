package it.fleetpulse.api.alert;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MaintenanceAlertRepository
    extends JpaRepository<MaintenanceAlertEntity, UUID>,
    JpaSpecificationExecutor<MaintenanceAlertEntity> {

    long countByStatus(AlertStatus status);

    @Query("""
        SELECT new it.fleetpulse.api.alert.MaintenanceAlertSummary(
            alert.id, alert.vehicleId, alert.type, alert.severity,
            alert.status, alert.description, alert.createdAt)
        FROM MaintenanceAlertEntity alert
        WHERE alert.status IN :statuses
        ORDER BY CASE alert.severity
            WHEN it.fleetpulse.api.alert.AlertSeverity.CRITICAL THEN 4
            WHEN it.fleetpulse.api.alert.AlertSeverity.HIGH THEN 3
            WHEN it.fleetpulse.api.alert.AlertSeverity.MEDIUM THEN 2
            WHEN it.fleetpulse.api.alert.AlertSeverity.LOW THEN 1
            ELSE 0 END DESC,
            alert.createdAt DESC, alert.id ASC
        """)
    List<MaintenanceAlertSummary> findRelevantAlerts(
        @Param("statuses") Collection<AlertStatus> statuses, Pageable pageable);
}
