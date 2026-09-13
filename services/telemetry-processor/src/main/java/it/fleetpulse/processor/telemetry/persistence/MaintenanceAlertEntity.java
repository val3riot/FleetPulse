package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.processor.telemetry.alert.AlertSeverity;
import it.fleetpulse.processor.telemetry.alert.AlertType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "maintenance_alerts")
public class MaintenanceAlertEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(name = "source_message_id", nullable = false, updatable = false)
    private UUID sourceMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 32)
    private AlertType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, updatable = false, length = 16)
    private AlertSeverity severity;

    @Column(name = "description", nullable = false, updatable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AlertStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    protected MaintenanceAlertEntity() {
    }

    public MaintenanceAlertEntity(UUID vehicleId, UUID sourceMessageId, AlertType type,
        AlertSeverity severity, String description, Instant createdAt) {
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        this.sourceMessageId =
            Objects.requireNonNull(sourceMessageId, "sourceMessageId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.severity = Objects.requireNonNull(severity, "severity must not be null");
        this.description = Objects.requireNonNull(description, "description must not be null");
        this.status = AlertStatus.OPEN;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public UUID getId() {
        return id;
    }

    public UUID getVehicleId() {
        return vehicleId;
    }

    public UUID getSourceMessageId() {
        return sourceMessageId;
    }

    public AlertType getType() {
        return type;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public String getDescription() {
        return description;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
