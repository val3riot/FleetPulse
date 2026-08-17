package it.fleetpulse.simulator.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReconnectPropertiesTest {

    @Test
    void acceptsPositiveOrderedBackoffs() {
        assertDoesNotThrow(
            () -> new ReconnectProperties(Duration.ofMillis(250), Duration.ofSeconds(5), 10, 0.2));
        assertDoesNotThrow(
            () -> new ReconnectProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 0));
    }

    @Test
    void rejectsMissingOrNonPositiveBackoffs() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectProperties(null, Duration.ofSeconds(1), 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectProperties(Duration.ZERO, Duration.ofSeconds(1), 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectProperties(Duration.ofMillis(1), null, 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectProperties(Duration.ofMillis(1), Duration.ofMillis(-1), 1, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectProperties(Duration.ofMillis(1), Duration.ofSeconds(1), 0, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectProperties(Duration.ofMillis(1), Duration.ofSeconds(1), 1, 1.1));
    }

    @Test
    void rejectsInitialBackoffGreaterThanMaximum() {
        assertThrows(IllegalArgumentException.class,
            () -> new ReconnectProperties(Duration.ofSeconds(2), Duration.ofSeconds(1), 1, 0));
    }
}
