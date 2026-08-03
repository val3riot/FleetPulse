package it.fleetpulse.api.common;

import io.swagger.v3.oas.annotations.media.Schema;

public record ValidationErrorDetail(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String field,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message
) {
}
