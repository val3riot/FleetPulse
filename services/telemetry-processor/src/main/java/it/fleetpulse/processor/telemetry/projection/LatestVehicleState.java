package it.fleetpulse.processor.telemetry.projection;

import java.time.Instant;
import java.util.UUID;

public record LatestVehicleState(
        UUID vehicleId,
        long lastSequenceNumber,
        Instant lastSeenAt,
        double speedKmh,
        double engineTemperatureC,
        double batteryVoltage,
        long odometerKm,
        double latitude,
        double longitude) {

    public boolean isNewerThan(LatestVehicleState current) {
        int comparison = lastSeenAt.compareTo(current.lastSeenAt());

        return comparison > 0
                || (comparison == 0
                        && lastSequenceNumber > current.lastSequenceNumber());
    }
}
