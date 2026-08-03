package it.fleetpulse.api.vehicle;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "vehicles")
public class VehicleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "external_code", nullable = false, unique = true, updatable = false, length = 64)
    private String externalCode;

    @Column(name = "plate", nullable = false, unique = true, length = 16)
    private String plate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private VehicleStatus status;

    @Column(name = "service_interval_km", nullable = false)
    private int serviceIntervalKm;

    @Column(name = "next_service_at_km", nullable = false)
    private long nextServiceAtKm;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Costruttore richiesto da JPA.
     */
    protected VehicleEntity() {
    }

    /**
     * Crea un veicolo con i dati persistibili assegnati dal servizio.
     */
    public VehicleEntity(
            String externalCode,
            String plate,
            VehicleStatus status,
            int serviceIntervalKm,
            long nextServiceAtKm,
            Instant createdAt) {
        this.externalCode = externalCode;
        this.plate = plate;
        this.status = status;
        this.serviceIntervalKm = serviceIntervalKm;
        this.nextServiceAtKm = nextServiceAtKm;
        this.createdAt = createdAt;
    }

    /** Restituisce l'identificativo del veicolo. */
    public UUID getId() {
        return id;
    }

    /** Restituisce il codice esterno del veicolo. */
    public String getExternalCode() {
        return externalCode;
    }

    /** Restituisce la targa del veicolo. */
    public String getPlate() {
        return plate;
    }

    /** Restituisce lo stato corrente del veicolo. */
    public VehicleStatus getStatus() {
        return status;
    }

    /** Restituisce l'intervallo chilometrico di manutenzione. */
    public int getServiceIntervalKm() {
        return serviceIntervalKm;
    }

    /** Restituisce il chilometraggio previsto per il prossimo tagliando. */
    public long getNextServiceAtKm() {
        return nextServiceAtKm;
    }

    /** Restituisce l'istante di creazione del veicolo. */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Cambia lo stato del veicolo. */
    public void changeStatus(VehicleStatus status) {
        this.status = Objects.requireNonNull(status);
    }
}
