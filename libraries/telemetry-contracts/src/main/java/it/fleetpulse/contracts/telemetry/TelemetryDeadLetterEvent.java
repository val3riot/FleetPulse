package it.fleetpulse.contracts.telemetry;

import java.time.Instant;
import java.util.Map;

public record TelemetryDeadLetterEvent(
        Instant failedAt,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        int attempts,
        String errorCode,
        String errorMessage,
        String originalKey, //string poiché rappresente la kafka key originale, anche se è un UUID
        Map<String, Object> originalPayload
) {
}