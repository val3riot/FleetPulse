package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.processor.telemetry.alert.AlertCandidate;
import it.fleetpulse.processor.telemetry.alert.AlertSeverity;
import it.fleetpulse.processor.telemetry.alert.AlertType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class MaintenanceAlertMapperTest {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID MESSAGE_ID =
        UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30.150Z");

    @Test
    void mapsCandidateToNewOpenEntity() {
        AlertCandidate candidate = new AlertCandidate(VEHICLE_ID, MESSAGE_ID,
            AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH,
            "Temperatura motore oltre soglia");

        MaintenanceAlertEntity entity = new MaintenanceAlertMapper().toEntity(candidate, CREATED_AT);

        assertAll(
            () -> assertThat(entity.getId()).isNull(),
            () -> assertThat(entity.getVehicleId()).isEqualTo(VEHICLE_ID),
            () -> assertThat(entity.getSourceMessageId()).isEqualTo(MESSAGE_ID),
            () -> assertThat(entity.getType()).isEqualTo(AlertType.ENGINE_TEMPERATURE_HIGH),
            () -> assertThat(entity.getSeverity()).isEqualTo(AlertSeverity.HIGH),
            () -> assertThat(entity.getDescription()).isEqualTo(candidate.description()),
            () -> assertThat(entity.getStatus()).isEqualTo(AlertStatus.OPEN),
            () -> assertThat(entity.getCreatedAt()).isEqualTo(CREATED_AT),
            () -> assertThat(entity.getAcknowledgedAt()).isNull(),
            () -> assertThat(entity.getClosedAt()).isNull()
        );
    }
}
