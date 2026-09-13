package it.fleetpulse.api.alert;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record MaintenanceAlertResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID vehicleId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID sourceMessageId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AlertType type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AlertSeverity severity,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AlertStatus status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
    Instant acknowledgedAt,
    Instant closedAt
) {
}
