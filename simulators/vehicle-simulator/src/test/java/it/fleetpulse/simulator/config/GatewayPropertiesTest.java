package it.fleetpulse.simulator.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GatewayPropertiesTest {

    @Test
    void acceptsValidHostAndTcpPort() {
        assertDoesNotThrow(() -> new GatewayProperties("localhost", 1, Duration.ofSeconds(1)));
        assertDoesNotThrow(() -> new GatewayProperties("telemetry-gateway", 65_535, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsBlankHostAndInvalidTcpPort() {
        assertThrows(IllegalArgumentException.class, () -> new GatewayProperties(null, 7000, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new GatewayProperties(" ", 7000, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new GatewayProperties("localhost", 0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new GatewayProperties("localhost", 65_536, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new GatewayProperties("localhost", 7000, Duration.ZERO));
    }
}
