package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.processor.telemetry.alert.AlertCandidate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public final class MaintenanceAlertMapper {

    public MaintenanceAlertEntity toEntity(AlertCandidate candidate, Instant createdAt) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");

        return new MaintenanceAlertEntity(candidate.vehicleId(), candidate.sourceMessageId(),
            candidate.type(), candidate.severity(), candidate.description(), createdAt);
    }
}
