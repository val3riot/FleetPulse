package it.fleetpulse.simulator.fleet.client;

import it.fleetpulse.simulator.fleet.model.FleetVehicle;

import java.util.Optional;

public interface FleetApiClient {
    Optional<FleetVehicle> findByExternalCode(String externalCode);
    FleetVehicle createVehicle(CreateFleetVehicleCommand command);
}
