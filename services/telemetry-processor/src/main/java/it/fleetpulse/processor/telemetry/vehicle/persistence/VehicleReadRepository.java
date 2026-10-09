package it.fleetpulse.processor.telemetry.vehicle.persistence;

import it.fleetpulse.processor.telemetry.alert.AlertVehicle;
import it.fleetpulse.processor.telemetry.vehicle.VehicleStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Transactional(readOnly = true)
public interface VehicleReadRepository extends Repository<VehicleReadEntity, UUID> {
    @Query("SELECT vehicle.status FROM VehicleReadEntity vehicle WHERE vehicle.id = :vehicleId")
    Optional<VehicleStatus> findStatus(@Param("vehicleId") UUID vehicleId);

    @Query("""
            SELECT new it.fleetpulse.processor.telemetry.alert.AlertVehicle(
                vehicle.id, vehicle.nextServiceAtKm)
            FROM VehicleReadEntity vehicle
            WHERE vehicle.id = :vehicleId
            """)
    Optional<AlertVehicle> findAlertVehicle(@Param("vehicleId") UUID vehicleId);
}
