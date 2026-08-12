package it.fleetpulse.simulator.fleet.http;

import java.time.Instant;
import java.util.UUID;

public record VehicleResponse(
        UUID id,
        String externalCode,
        String plate,
        VehicleStatus status,
        int serviceIntervalKm,
        long nextServiceAtKm,
        Instant createdAt
) {

}
