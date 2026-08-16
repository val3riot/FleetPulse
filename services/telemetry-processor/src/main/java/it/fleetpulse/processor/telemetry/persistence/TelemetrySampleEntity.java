package it.fleetpulse.processor.telemetry.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telemetry_samples")
public class TelemetrySampleEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(
            name = "message_id",
            nullable = false,
            unique = true,
            updatable = false
    )
    private UUID messageId;

    @Column(name = "vehicle_id", nullable = false, updatable = false)
    private UUID vehicleId;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "observed_at", nullable = false, updatable = false)
    private Instant observedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "processed_at", nullable = false, updatable = false)
    private Instant processedAt;

    @Column(name = "speed_kmh", nullable = false, updatable = false)
    private double speedKmh;

    @Column(
            name = "engine_temperature_c",
            nullable = false,
            updatable = false
    )
    private double engineTemperatureC;

    @Column(name = "battery_voltage", nullable = false, updatable = false)
    private double batteryVoltage;

    @Column(name = "odometer_km", nullable = false, updatable = false)
    private long odometerKm;

    @Column(name = "latitude", nullable = false, updatable = false)
    private double latitude;

    @Column(name = "longitude", nullable = false, updatable = false)
    private double longitude;

    protected TelemetrySampleEntity() {
    }

    public TelemetrySampleEntity(
            UUID messageId,
            UUID vehicleId,
            long sequenceNumber,
            Instant observedAt,
            Instant receivedAt,
            Instant processedAt,
            double speedKmh,
            double engineTemperatureC,
            double batteryVoltage,
            long odometerKm,
            double latitude,
            double longitude
    ) {
        this.messageId = messageId;
        this.vehicleId = vehicleId;
        this.sequenceNumber = sequenceNumber;
        this.observedAt = observedAt;
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
        this.speedKmh = speedKmh;
        this.engineTemperatureC = engineTemperatureC;
        this.batteryVoltage = batteryVoltage;
        this.odometerKm = odometerKm;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public Long getId() {
        return id;
    }

    public UUID getMessageId() {
        return messageId;
    }

    public UUID getVehicleId() {
        return vehicleId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public double getSpeedKmh() {
        return speedKmh;
    }

    public double getEngineTemperatureC() {
        return engineTemperatureC;
    }

    public double getBatteryVoltage() {
        return batteryVoltage;
    }

    public long getOdometerKm() {
        return odometerKm;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}
