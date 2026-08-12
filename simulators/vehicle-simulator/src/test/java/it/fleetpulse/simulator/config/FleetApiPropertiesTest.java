package it.fleetpulse.simulator.config;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FleetApiPropertiesTest {

    @Test
    void acceptsHttpAndHttpsUrls() {
        assertDoesNotThrow(() -> new FleetApiProperties(URI.create("http://localhost:8080")));
        assertDoesNotThrow(() -> new FleetApiProperties(URI.create("https://fleet.example.com")));
    }

    @Test
    void rejectsMissingRelativeOrUnsupportedUrl() {
        assertThrows(IllegalArgumentException.class, () -> new FleetApiProperties(null));
        assertThrows(IllegalArgumentException.class, () -> new FleetApiProperties(URI.create("/api/v1")));
        assertThrows(IllegalArgumentException.class, () -> new FleetApiProperties(URI.create("postgres://localhost/db")));
        assertThrows(IllegalArgumentException.class, () -> new FleetApiProperties(URI.create("http:missing-host")));
    }
}
