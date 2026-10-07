package it.fleetpulse.api.vehicle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface VehicleRepository extends JpaRepository<VehicleEntity, UUID>,
    JpaSpecificationExecutor<VehicleEntity> {
    @Query("""
        SELECT new it.fleetpulse.api.vehicle.VehicleStatusCount(vehicle.status, COUNT(vehicle))
        FROM VehicleEntity vehicle
        GROUP BY vehicle.status
        """)
    List<VehicleStatusCount> countVehiclesByStatus();

    /**
     * Verifica se esiste già un veicolo con il codice esterno indicato.
     */
    boolean existsByExternalCode(String externalCode);

    /**
     * Verifica se esiste già un veicolo con la targa indicata.
     */
    boolean existsByPlate(String plate);
}
