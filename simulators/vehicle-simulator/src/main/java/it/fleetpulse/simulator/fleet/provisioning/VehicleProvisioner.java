package it.fleetpulse.simulator.fleet.provisioning;

import it.fleetpulse.simulator.config.VehicleSimulatorProperties;
import it.fleetpulse.simulator.fleet.client.CreateFleetVehicleCommand;
import it.fleetpulse.simulator.fleet.client.FleetApiClient;
import it.fleetpulse.simulator.fleet.client.VehicleAlreadyExistsException;
import it.fleetpulse.simulator.fleet.model.FleetVehicle;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import it.fleetpulse.simulator.fleet.model.SimulatorVehicleDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public final class VehicleProvisioner implements FleetProvisioner {

    private final FleetApiClient fleetApiClient;
    private final VehicleSimulatorProperties properties;

    public VehicleProvisioner(
            FleetApiClient fleetApiClient,
            VehicleSimulatorProperties properties
    ) {
        this.fleetApiClient = fleetApiClient;
        this.properties = properties;
    }

    @Override
    public List<ProvisionedVehicle> provision() {
        List<ProvisionedVehicle> vehicles =
                new ArrayList<>(properties.vehicleCount());

        for (int index = 1; index <= properties.vehicleCount(); index++) {
            SimulatorVehicleDefinition definition =
                    SimulatorVehicleDefinition.of(index, properties.vehicle());

            FleetVehicle vehicle =
                    provision(definition);

            vehicles.add(
                    new ProvisionedVehicle(
                            vehicle.id(),
                            vehicle.externalCode()
                    )
            );
        }

        return List.copyOf(vehicles);
    }

    private FleetVehicle provision(
            SimulatorVehicleDefinition definition
    ) {
        Optional<FleetVehicle> existing =
                fleetApiClient.findByExternalCode(
                        definition.externalCode()
                );

        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            return fleetApiClient.createVehicle(
                    new CreateFleetVehicleCommand(
                            definition.externalCode(),
                            definition.plate(),
                            definition.serviceIntervalKm(),
                            definition.nextServiceAtKm()
                    )
            );

        } catch (VehicleAlreadyExistsException exception) {
            return fleetApiClient.findByExternalCode(
                            definition.externalCode()
                    )
                    .orElseThrow(() ->
                            new VehicleProvisioningException(
                                    "Vehicle "
                                            + definition.externalCode()
                                            + " returned conflict but could not be found"
                            )
                    );
        }
    }
}
