package it.fleetpulse.processor.telemetry.vehicle;

import it.fleetpulse.processor.telemetry.vehicle.persistence.VehicleReadRepository;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgreSqlVehicleRegistry implements VehicleRegistry {

    private final VehicleReadRepository vehicles;

    public PostgreSqlVehicleRegistry(VehicleReadRepository vehicles) {
        this.vehicles = Objects.requireNonNull(vehicles, "vehicles must not be null");
    }

    @Override
    public Optional<VehicleStatus> findStatus(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        return vehicles.findStatus(vehicleId);
    }
}
