package it.fleetpulse.simulator.fleet.provisioning;

import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;

import java.util.List;

@FunctionalInterface
public interface FleetProvisioner {

    List<ProvisionedVehicle> provision();
}
