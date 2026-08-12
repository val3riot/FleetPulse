package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;

@FunctionalInterface
public interface VehicleWorkloadProvider {

    VehicleTask create(ProvisionedVehicle vehicle);
}
