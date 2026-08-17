package it.fleetpulse.api.vehicle;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record VehicleResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String externalCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String plate,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) VehicleStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int serviceIntervalKm,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long nextServiceAtKm,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt
) {
}
