package it.fleetpulse.api.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import it.fleetpulse.api.alert.AlertSeverity;
import it.fleetpulse.api.alert.AlertStatus;
import it.fleetpulse.api.alert.AlertType;

import java.time.Instant;
import java.util.UUID;

public record DashboardAlertResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID vehicleId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AlertType type,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AlertSeverity severity,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AlertStatus status,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String description,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt
) {
}
