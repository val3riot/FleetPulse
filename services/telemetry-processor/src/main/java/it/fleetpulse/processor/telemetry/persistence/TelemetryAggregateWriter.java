package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.processor.telemetry.alert.AlertCandidate;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Component
public class TelemetryAggregateWriter {
    private final TelemetrySampleRepository sampleRepository;
    private final MaintenanceAlertRepository alertRepository;
    private final MaintenanceAlertMapper alertMapper;

    public TelemetryAggregateWriter(TelemetrySampleRepository sampleRepository,
        MaintenanceAlertRepository alertRepository, MaintenanceAlertMapper alertMapper) {
        this.sampleRepository =
            Objects.requireNonNull(sampleRepository, "sampleRepository must not be null");
        this.alertRepository =
            Objects.requireNonNull(alertRepository, "alertRepository must not be null");
        this.alertMapper = Objects.requireNonNull(alertMapper, "alertMapper must not be null");
    }

    @Transactional
    public TelemetryAggregateWriteResult insert(TelemetrySampleEntity sample,
        List<AlertCandidate> candidates, Instant alertsCreatedAt) {
        Objects.requireNonNull(sample, "sample must not be null");
        Objects.requireNonNull(candidates, "candidates must not be null");
        Objects.requireNonNull(alertsCreatedAt, "alertsCreatedAt must not be null");

        TelemetrySampleEntity savedSample = sampleRepository.saveAndFlush(sample);
        List<MaintenanceAlertEntity> alerts = candidates.stream()
            .map(candidate -> alertMapper.toEntity(candidate, alertsCreatedAt))
            .toList();
        List<MaintenanceAlertEntity> savedAlerts = alerts.isEmpty()
            ? List.of()
            : alertRepository.saveAllAndFlush(alerts);

        return new TelemetryAggregateWriteResult(savedSample, savedAlerts);
    }
}
