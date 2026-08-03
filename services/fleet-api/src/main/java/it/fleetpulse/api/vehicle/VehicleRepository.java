package it.fleetpulse.api.vehicle;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface VehicleRepository extends JpaRepository<VehicleEntity, UUID>, JpaSpecificationExecutor<VehicleEntity> {
    /**
     * Verifica se esiste già un veicolo con il codice esterno indicato.
     */
    boolean existsByExternalCode(String externalCode);

    /**
     * Verifica se esiste già un veicolo con la targa indicata.
     */
    boolean existsByPlate(String plate);
}
