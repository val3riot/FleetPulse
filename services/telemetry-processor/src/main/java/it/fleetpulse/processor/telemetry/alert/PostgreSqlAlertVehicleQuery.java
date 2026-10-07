package it.fleetpulse.processor.telemetry.alert;

import it.fleetpulse.processor.telemetry.vehicle.persistence.VehicleReadRepository;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgreSqlAlertVehicleQuery implements AlertVehicleQuery {
    private final VehicleReadRepository vehicles;

    public PostgreSqlAlertVehicleQuery(VehicleReadRepository vehicles) {
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles must not be null");
    }

    @Override
    public Optional<AlertVehicle> findById(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        return vehicles.findAlertVehicle(vehicleId);
    }
}
