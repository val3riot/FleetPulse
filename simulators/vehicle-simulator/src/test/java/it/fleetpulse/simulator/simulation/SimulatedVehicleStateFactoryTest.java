package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.config.VehicleProperties;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulatedVehicleStateFactoryTest {

    @Test
    void createsIndependentStateForEveryProvisionedVehicle() {
        ProvisionedVehicle first = vehicle("FP-SIM-001");
        ProvisionedVehicle second = vehicle("FP-SIM-002");
        SimulatedVehicleStateFactory factory = factory();

        List<SimulatedVehicleState> states = factory.create(List.of(first, second));
        SimulatedVehicleState firstNext = states.get(0).next(10, 85, 13.8, 10_001, 41.9, 12.5);

        assertEquals(2, states.size());
        assertEquals(0, states.get(0).sequenceNumber());
        assertEquals(0, states.get(1).sequenceNumber());
        assertEquals(1, firstNext.sequenceNumber());
        assertEquals(0, states.get(1).sequenceNumber());
        assertEquals(first.vehicleId(), states.get(0).vehicleId());
        assertEquals(second.vehicleId(), states.get(1).vehicleId());
    }

    @Test
    void recreatingRuntimeStateStartsNewProcessSequenceAtZero() {
        ProvisionedVehicle vehicle = vehicle("FP-SIM-001");
        SimulatedVehicleStateFactory factory = factory();

        SimulatedVehicleState firstStartup =
            factory.create(vehicle).next(10, 85, 13.8, 10_001, 41.9, 12.5);
        SimulatedVehicleState secondStartup = factory.create(vehicle);

        assertEquals(1, firstStartup.sequenceNumber());
        assertEquals(0, secondStartup.sequenceNumber());
        assertEquals(firstStartup.vehicleId(), secondStartup.vehicleId());
    }

    @Test
    void rejectsInvalidInitialCoordinates() {
        assertThrows(IllegalArgumentException.class,
            () -> new SimulatedVehicleStateFactory(new VehicleProperties(15_000, 10_000), 91,
                12.5));
    }

    private static SimulatedVehicleStateFactory factory() {
        return new SimulatedVehicleStateFactory(new VehicleProperties(15_000, 10_000), 41.9028,
            12.4964);
    }

    private static ProvisionedVehicle vehicle(String externalCode) {
        return new ProvisionedVehicle(UUID.randomUUID(), externalCode);
    }
}
