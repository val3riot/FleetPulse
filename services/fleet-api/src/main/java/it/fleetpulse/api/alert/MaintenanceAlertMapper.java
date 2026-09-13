package it.fleetpulse.api.alert;

import org.springframework.stereotype.Component;

@Component
public class MaintenanceAlertMapper {

    public MaintenanceAlertResponse toResponse(MaintenanceAlertEntity entity) {
        return new MaintenanceAlertResponse(entity.getId(), entity.getVehicleId(),
            entity.getSourceMessageId(), entity.getType(), entity.getSeverity(),
            entity.getDescription(), entity.getStatus(), entity.getCreatedAt(),
            entity.getAcknowledgedAt(), entity.getClosedAt());
    }
}
