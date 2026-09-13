package it.fleetpulse.processor.telemetry.persistence;

import java.util.List;
import java.util.Objects;

public record TelemetryAggregateWriteResult(
        TelemetrySampleEntity sample,
        List<MaintenanceAlertEntity> alerts
) {
    public TelemetryAggregateWriteResult {
        Objects.requireNonNull(sample, "sample must not be null");
        Objects.requireNonNull(alerts, "alerts must not be null");
        alerts = List.copyOf(alerts);
    }
}
