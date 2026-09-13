package it.fleetpulse.api.telemetry;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/**
 * Pagina REST dello storico telemetrico di un veicolo.
 */
public record TelemetryHistoryResponse(
    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    List<@Valid TelemetrySampleResponse> content,

    @PositiveOrZero
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    int page,

    @Positive
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
    int size,

    @PositiveOrZero
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    long totalElements,

    @PositiveOrZero
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    int totalPages,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    boolean first,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    boolean last
) {
}
