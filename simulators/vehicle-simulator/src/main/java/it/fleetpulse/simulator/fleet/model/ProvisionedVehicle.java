package it.fleetpulse.simulator.fleet.model;

import java.util.Objects;
import java.util.UUID;

public record ProvisionedVehicle(
    UUID vehicleId,
    String externalCode
) {

    public ProvisionedVehicle {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        if (externalCode == null || externalCode.isBlank()) {
            throw new IllegalArgumentException("externalCode must not be blank");
        }
    }
}
