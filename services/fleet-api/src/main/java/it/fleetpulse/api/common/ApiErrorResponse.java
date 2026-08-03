package it.fleetpulse.api.common;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

public record ApiErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant timestamp,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String path,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ValidationErrorDetail> details
) {
}
