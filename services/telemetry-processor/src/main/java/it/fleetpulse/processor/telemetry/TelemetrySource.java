package it.fleetpulse.processor.telemetry;

import java.util.Objects;

public record TelemetrySource(
        String topic,
        int partition,
        long offset
) {

    public TelemetrySource {
        Objects.requireNonNull(topic, "topic must not be null");

        if (topic.isBlank()) {
            throw new IllegalArgumentException("topic must not be blank");
        }
        if (partition < 0) {
            throw new IllegalArgumentException("partition must not be negative");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }
}
