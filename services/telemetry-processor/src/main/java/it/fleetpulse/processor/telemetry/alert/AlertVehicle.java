package it.fleetpulse.processor.telemetry.alert;

import java.util.Objects;
import java.util.UUID;

public record AlertVehicle(UUID id, long nextServiceAtKm) {

    public AlertVehicle {
        Objects.requireNonNull(id, "id must not be null");
        if (nextServiceAtKm < 0) {
            throw new IllegalArgumentException("nextServiceAtKm must not be negative");
        }
    }
}
