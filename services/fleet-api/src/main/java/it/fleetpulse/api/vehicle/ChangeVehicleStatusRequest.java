package it.fleetpulse.api.vehicle;

import jakarta.validation.constraints.NotNull;

public record ChangeVehicleStatusRequest(
        @NotNull VehicleStatus status
) {
}
