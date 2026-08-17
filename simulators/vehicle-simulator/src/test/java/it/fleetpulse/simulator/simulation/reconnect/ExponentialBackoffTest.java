package it.fleetpulse.simulator.simulation.reconnect;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExponentialBackoffTest {

    @Test
    void doublesDelayAndSaturatesAtMaximum() {
        ExponentialBackoff backoff =
            new ExponentialBackoff(Duration.ofMillis(250), Duration.ofSeconds(5));

        assertEquals(Duration.ofMillis(250), backoff.nextDelay());
        assertEquals(Duration.ofMillis(500), backoff.nextDelay());
        assertEquals(Duration.ofSeconds(1), backoff.nextDelay());
        assertEquals(Duration.ofSeconds(2), backoff.nextDelay());
        assertEquals(Duration.ofSeconds(4), backoff.nextDelay());
        assertEquals(Duration.ofSeconds(5), backoff.nextDelay());
        assertEquals(Duration.ofSeconds(5), backoff.nextDelay());
    }

    @Test
    void resetsToInitialDelay() {
        ExponentialBackoff backoff =
            new ExponentialBackoff(Duration.ofMillis(100), Duration.ofSeconds(1));

        backoff.nextDelay();
        backoff.nextDelay();
        backoff.reset();

        assertEquals(Duration.ofMillis(100), backoff.nextDelay());
    }

    @Test
    void rejectsInvalidBounds() {
        assertThrows(NullPointerException.class,
            () -> new ExponentialBackoff(null, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new ExponentialBackoff(Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
            () -> new ExponentialBackoff(Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }
}
