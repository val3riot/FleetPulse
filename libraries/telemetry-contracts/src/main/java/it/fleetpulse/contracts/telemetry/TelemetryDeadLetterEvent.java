package it.fleetpulse.contracts.telemetry;

import java.time.Instant;
import java.util.Map;

/**
 * Evento terminale contenente il payload che non è stato elaborato.
 *
 * @param originalKey chiave Kafka originale, che può anche non essere un UUID
 */
public record TelemetryDeadLetterEvent(
        Instant failedAt,
        String sourceTopic,
        int sourcePartition,
        long sourceOffset,
        int attempts,
        String errorCode,
        String errorMessage,
        String originalKey,
        Map<String, Object> originalPayload
) {
}
