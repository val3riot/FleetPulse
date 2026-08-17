package it.fleetpulse.api.vehicle;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record CreateVehicleRequest(
        @NotBlank @Size(max = 64) String externalCode,
        @NotBlank @Size(max = 16) String plate,
        @NotNull @Positive Integer serviceIntervalKm,
        @NotNull @PositiveOrZero Long nextServiceAtKm
) {
}
