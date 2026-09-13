package it.fleetpulse.processor.telemetry.alert;

import java.util.Objects;
import java.util.UUID;

public record AlertCandidate(
        UUID vehicleId,
        UUID sourceMessageId,
        AlertType type,
        AlertSeverity severity,
        String description
) {
    public static final int MAX_DESCRIPTION_LENGTH = 255;

    public AlertCandidate {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        Objects.requireNonNull(sourceMessageId, "sourceMessageId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(severity, "severity must not be null");
        Objects.requireNonNull(description, "description must not be null");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException("description must not exceed 255 characters");
        }
    }
}
