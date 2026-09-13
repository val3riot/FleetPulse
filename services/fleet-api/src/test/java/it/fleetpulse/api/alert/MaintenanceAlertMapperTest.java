package it.fleetpulse.api.alert;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MaintenanceAlertMapperTest {

    @Test
    void mapsEveryResponseField() {
        UUID id = UUID.fromString("f2607610-5100-4723-93d0-e6bbdcf00da0");
        UUID vehicleId = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
        UUID sourceMessageId = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
        Instant createdAt = Instant.parse("2026-08-01T10:16:00Z");
        Instant acknowledgedAt = createdAt.plusSeconds(60);
        MaintenanceAlertEntity entity = mock(MaintenanceAlertEntity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getVehicleId()).thenReturn(vehicleId);
        when(entity.getSourceMessageId()).thenReturn(sourceMessageId);
        when(entity.getType()).thenReturn(AlertType.ENGINE_TEMPERATURE_HIGH);
        when(entity.getSeverity()).thenReturn(AlertSeverity.HIGH);
        when(entity.getDescription()).thenReturn("Temperatura motore oltre soglia");
        when(entity.getStatus()).thenReturn(AlertStatus.ACKNOWLEDGED);
        when(entity.getCreatedAt()).thenReturn(createdAt);
        when(entity.getAcknowledgedAt()).thenReturn(acknowledgedAt);

        MaintenanceAlertResponse response = new MaintenanceAlertMapper().toResponse(entity);

        assertThat(response).isEqualTo(new MaintenanceAlertResponse(id, vehicleId,
            sourceMessageId, AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH,
            "Temperatura motore oltre soglia", AlertStatus.ACKNOWLEDGED, createdAt,
            acknowledgedAt, null));
    }
}
