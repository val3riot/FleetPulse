package it.fleetpulse.contracts.telemetry;

import java.time.Instant;
import java.util.UUID;

public record TelemetryRejectedEvent(
        UUID messageId,
        UUID vehicleId,
        TelemetryRejectionReason reason,
        Instant rejectedAt,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset
) {
}
