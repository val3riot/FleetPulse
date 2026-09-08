package it.fleetpulse.api.state.redis;

import java.time.Instant;
import java.util.UUID;

public record RedisLatestVehicleState(
    UUID vehicleId,
    long lastSequenceNumber,
    Instant lastSeenAt,
    double speedKmh,
    double engineTemperatureC,
    double batteryVoltage,
    long odometerKm,
    double latitude,
    double longitude
) {
}
