package it.fleetpulse.api.alert;

import java.time.Instant;
import java.util.UUID;

/** Projection di lettura indipendente dal DTO REST della dashboard. */
public record MaintenanceAlertSummary(
    UUID id,
    UUID vehicleId,
    AlertType type,
    AlertSeverity severity,
    AlertStatus status,
    String description,
    Instant createdAt
) {
}
