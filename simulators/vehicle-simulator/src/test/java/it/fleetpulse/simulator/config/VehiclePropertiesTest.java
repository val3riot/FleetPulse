package it.fleetpulse.simulator.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VehiclePropertiesTest {

    @Test
    void acceptsValidVehicleDefaults() {
        assertDoesNotThrow(() -> new VehicleProperties(15_000, 10_000.5));
    }

    @Test
    void rejectsNonPositiveServiceInterval() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleProperties(0, 10_000));
        assertThrows(IllegalArgumentException.class, () -> new VehicleProperties(-1, 10_000));
    }

    @Test
    void rejectsInvalidInitialOdometer() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleProperties(15_000, -1));
        assertThrows(IllegalArgumentException.class, () -> new VehicleProperties(15_000, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new VehicleProperties(15_000, Double.POSITIVE_INFINITY));
    }
}
