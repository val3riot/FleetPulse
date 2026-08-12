package it.fleetpulse.simulator.fleet.model;

import java.util.Objects;
import java.util.UUID;

public record FleetVehicle(
        UUID id,
        String externalCode,
        String plate
) {
    public FleetVehicle {
        Objects.requireNonNull(id, "id must not be null");
        if (externalCode == null || externalCode.isBlank()) {
            throw new IllegalArgumentException("externalCode must not be blank");
        }
        if (plate == null || plate.isBlank()) {
            throw new IllegalArgumentException("plate must not be blank");
        }
    }
}
