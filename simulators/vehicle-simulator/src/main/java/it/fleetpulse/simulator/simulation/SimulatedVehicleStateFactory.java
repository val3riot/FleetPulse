package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.config.VehicleProperties;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;

import java.util.List;
import java.util.Objects;

public final class SimulatedVehicleStateFactory {

    private final VehicleProperties properties;
    private final double initialLatitude;
    private final double initialLongitude;

    public SimulatedVehicleStateFactory(VehicleProperties properties, double initialLatitude,
        double initialLongitude) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        requireCoordinate(initialLatitude, -90.0, 90.0, "initialLatitude");
        requireCoordinate(initialLongitude, -180.0, 180.0, "initialLongitude");
        this.initialLatitude = initialLatitude;
        this.initialLongitude = initialLongitude;
    }

    public List<SimulatedVehicleState> create(List<ProvisionedVehicle> vehicles) {
        Objects.requireNonNull(vehicles, "vehicles must not be null");
        return vehicles.stream().map(this::create).toList();
    }

    public SimulatedVehicleState create(ProvisionedVehicle vehicle) {
        return SimulatedVehicleState.initial(vehicle, properties.initialOdometerKm(),
            initialLatitude, initialLongitude);
    }

    private static void requireCoordinate(double value, double minimum, double maximum,
        String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                field + " must be finite and between " + minimum + " and " + maximum);
        }
    }
}
