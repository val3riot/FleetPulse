package it.fleetpulse.api.alert;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceAlertSearchCriteria(
    UUID vehicleId,
    AlertStatus status,
    AlertType type,
    AlertSeverity severity,
    Instant from,
    Instant to
) {
}
