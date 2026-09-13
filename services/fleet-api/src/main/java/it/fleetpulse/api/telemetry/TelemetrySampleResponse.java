package it.fleetpulse.api.telemetry;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.UUID;

/**
 * Rappresentazione REST di un sample dello storico telemetrico.
 */
public record TelemetrySampleResponse(
    @NotNull @Positive
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
    Long id,

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    UUID messageId,

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    UUID vehicleId,

    @PositiveOrZero
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    long sequenceNumber,

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Instant observedAt,

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Instant receivedAt,

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    Instant processedAt,

    @PositiveOrZero
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    double speedKmh,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double engineTemperatureC,

    @PositiveOrZero
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    double batteryVoltage,

    @PositiveOrZero
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0")
    long odometerKm,

    @DecimalMin("-90.0") @DecimalMax("90.0")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "-90", maximum = "90")
    double latitude,

    @DecimalMin("-180.0") @DecimalMax("180.0")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "-180", maximum = "180")
    double longitude
) {
}
