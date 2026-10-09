package it.fleetpulse.api.alert;

import jakarta.validation.constraints.NotNull;

public record ChangeAlertStatusRequest(
        @NotNull AlertStatusTarget status) {
}
