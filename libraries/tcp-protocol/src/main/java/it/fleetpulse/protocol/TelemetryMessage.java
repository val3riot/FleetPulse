package it.fleetpulse.protocol;

import java.time.Instant;
import java.util.UUID;

public record TelemetryMessage(
        int protocolVersion,
        UUID messageId,
        UUID vehicleId,
        long sequenceNumber,
        Instant observedAt,
        double speedKmh,
        double engineTemperatureC,
        double batteryVoltage,
        long odometerKm,
        double latitude,
        double longitude
) {
}
