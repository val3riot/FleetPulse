package it.fleetpulse.api.telemetry;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * Parametri di ricerca dello storico telemetrico.
 */
public record TelemetryHistoryRequest(
    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Inizio incluso dell'intervallo UTC", example = "2026-08-01T10:00:00Z")
    Instant from,

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Fine inclusa dell'intervallo UTC", example = "2026-08-01T11:00:00Z")
    Instant to,

    @Min(0)
    @Schema(minimum = "0", defaultValue = "0")
    Integer page,

    @Min(1)
    @Max(100)
    @Schema(minimum = "1", maximum = "100", defaultValue = "50")
    Integer size,

    @NotBlank
    @Pattern(regexp = "observedAt,(asc|desc)")
    @Schema(description = "Ordinamento per istante di osservazione",
        allowableValues = {"observedAt,asc", "observedAt,desc"},
        defaultValue = "observedAt,desc")
    String sort
) {
    public TelemetryHistoryRequest {
        page = page == null ? 0 : page;
        size = size == null ? 50 : size;
        sort = sort == null ? "observedAt,desc" : sort;
    }
}
