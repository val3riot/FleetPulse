package it.fleetpulse.processor.telemetry.vehicle.persistence;

import it.fleetpulse.processor.telemetry.vehicle.VehicleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.util.UUID;

/** Mapping locale di sola lettura dei dati veicolo necessari al processor. */
@Entity
@Immutable
@Table(name = "vehicles")
public class VehicleReadEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 16)
    private VehicleStatus status;

    @Column(name = "next_service_at_km", nullable = false, updatable = false)
    private long nextServiceAtKm;

    protected VehicleReadEntity() {
    }
}
