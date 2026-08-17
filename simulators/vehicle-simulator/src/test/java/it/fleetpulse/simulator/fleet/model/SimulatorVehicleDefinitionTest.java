package it.fleetpulse.simulator.fleet.model;

import it.fleetpulse.simulator.config.VehicleProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulatorVehicleDefinitionTest {

    @Test
    void createsStableDefinitionFromIndex() {
        VehicleProperties properties = new VehicleProperties(15_000, 10_000.5);

        SimulatorVehicleDefinition definition = SimulatorVehicleDefinition.of(1, properties);

        assertEquals("FP-SIM-001", definition.externalCode());
        assertEquals("SIM001", definition.plate());
        assertEquals(15_000, definition.serviceIntervalKm());
        assertEquals(25_001, definition.nextServiceAtKm());
        assertEquals(definition, SimulatorVehicleDefinition.of(1, properties));
    }

    @Test
    void rejectsNonPositiveIndex() {
        assertThrows(IllegalArgumentException.class,
            () -> SimulatorVehicleDefinition.of(0, new VehicleProperties(15_000, 10_000)));
    }
}
