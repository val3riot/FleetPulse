package it.fleetpulse.processor.telemetry.alert;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface AlertVehicleQuery {

    Optional<AlertVehicle> findById(UUID vehicleId);
}
