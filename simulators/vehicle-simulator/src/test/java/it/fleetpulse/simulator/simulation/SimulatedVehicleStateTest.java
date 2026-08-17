package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SimulatedVehicleStateTest {

    @Test
    void createsNominalInitialStateWithSequenceZero() {
        UUID vehicleId = UUID.randomUUID();

        SimulatedVehicleState state =
            SimulatedVehicleState.initial(new ProvisionedVehicle(vehicleId, "FP-SIM-001"), 10_000.5,
                41.9028, 12.4964);

        assertEquals(vehicleId, state.vehicleId());
        assertEquals("FP-SIM-001", state.externalCode());
        assertEquals(0, state.sequenceNumber());
        assertEquals(0.0, state.speedKmh());
        assertEquals(85.0, state.engineTemperatureC());
        assertEquals(13.8, state.batteryVoltage());
        assertEquals(10_000.5, state.odometerKm());
    }

    @Test
    void returnsNewStateAndAdvancesSequenceExactlyOnce() {
        SimulatedVehicleState current = initial("FP-SIM-001");

        SimulatedVehicleState next = current.next(72, 89, 13.7, 10_000.02, 41.9, 12.5);

        assertNotSame(current, next);
        assertEquals(0, current.sequenceNumber());
        assertEquals(1, next.sequenceNumber());
        assertEquals(current.vehicleId(), next.vehicleId());
        assertEquals(current.externalCode(), next.externalCode());
        assertEquals(72, next.speedKmh());
    }

    @Test
    void rejectsOdometerRegressionAndSequenceOverflow() {
        SimulatedVehicleState current = initial("FP-SIM-001");
        assertThrows(IllegalArgumentException.class,
            () -> current.next(10, 85, 13.8, 9_999, 41.9, 12.5));

        SimulatedVehicleState exhausted =
            new SimulatedVehicleState(current.vehicleId(), current.externalCode(), Long.MAX_VALUE,
                0, 85, 13.8, 10_000, 41.9, 12.5);
        assertThrows(IllegalStateException.class,
            () -> exhausted.next(10, 85, 13.8, 10_001, 41.9, 12.5));
    }

    @Test
    void validatesTelemetryBounds() {
        SimulatedVehicleState current = initial("FP-SIM-001");

        assertThrows(IllegalArgumentException.class,
            () -> current.next(Double.NaN, 85, 13.8, 10_000, 41.9, 12.5));
        assertThrows(IllegalArgumentException.class,
            () -> current.next(10, 85, -1, 10_000, 41.9, 12.5));
        assertThrows(IllegalArgumentException.class,
            () -> current.next(10, 85, 13.8, 10_000, 91, 12.5));
    }

    private static SimulatedVehicleState initial(String externalCode) {
        return SimulatedVehicleState.initial(
            new ProvisionedVehicle(UUID.randomUUID(), externalCode), 10_000, 41.9028, 12.4964);
    }
}
