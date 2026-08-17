package it.fleetpulse.contracts.telemetry;

import java.time.Instant;
import java.util.UUID;

public record TelemetryEvent(
        int eventVersion,
        UUID messageId,
        UUID vehicleId,
        long sequenceNumber,
        Instant observedAt,
        Instant receivedAt,
        TelemetryData telemetry
) {
}
