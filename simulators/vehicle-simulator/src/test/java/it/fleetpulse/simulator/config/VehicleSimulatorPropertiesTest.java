package it.fleetpulse.simulator.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VehicleSimulatorPropertiesTest {

    private static final FleetApiProperties FLEET_API =
            new FleetApiProperties(URI.create("http://localhost:8080"));
    private static final GatewayProperties GATEWAY =
            new GatewayProperties("localhost", 7000, Duration.ofSeconds(3));
    private static final ReconnectProperties RECONNECT =
            new ReconnectProperties(Duration.ofMillis(250), Duration.ofSeconds(5), 10, 0.2);
    private static final VehicleProperties VEHICLE = new VehicleProperties(15_000, 10_000);

    @Test
    void acceptsValidConfiguration() {
        assertDoesNotThrow(() -> properties(5, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsNonPositiveVehicleCount() {
        assertThrows(IllegalArgumentException.class, () -> properties(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> properties(-1, Duration.ofSeconds(1)));
    }

    @Test
    void rejectsNonPositiveSendInterval() {
        assertThrows(IllegalArgumentException.class, () -> properties(5, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> properties(5, Duration.ofMillis(-1)));
    }

    @Test
    void rejectsMissingConfigurationGroup() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleSimulatorProperties(
                true, 5, null, GATEWAY, Duration.ofSeconds(1), Duration.ofSeconds(5), RECONNECT, VEHICLE));
    }

    @Test
    void bindsExternalConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfiguration.class)
                .withPropertyValues(
                        "simulator.enabled=true",
                        "simulator.vehicle-count=3",
                        "simulator.fleet-api.base-url=http://fleet-api:8080",
                        "simulator.gateway.host=telemetry-gateway",
                        "simulator.gateway.port=7000",
                        "simulator.gateway.connect-timeout=2s",
                        "simulator.send-interval=500ms",
                        "simulator.shutdown-grace-period=3s",
                        "simulator.reconnect.initial-backoff=100ms",
                        "simulator.reconnect.max-backoff=2s",
                        "simulator.reconnect.max-attempts=7",
                        "simulator.reconnect.jitter-ratio=0.15",
                        "simulator.vehicle.service-interval-km=12000",
                        "simulator.vehicle.initial-odometer-km=5000.5"
                )
                .run(context -> {
                    VehicleSimulatorProperties properties = context.getBean(VehicleSimulatorProperties.class);
                    assertEquals(3, properties.vehicleCount());
                    assertEquals(URI.create("http://fleet-api:8080"), properties.fleetApi().baseUrl());
                    assertEquals("telemetry-gateway", properties.gateway().host());
                    assertEquals(Duration.ofSeconds(2), properties.gateway().connectTimeout());
                    assertEquals(Duration.ofMillis(500), properties.sendInterval());
                    assertEquals(Duration.ofSeconds(3), properties.shutdownGracePeriod());
                    assertEquals(Duration.ofMillis(100), properties.reconnect().initialBackoff());
                    assertEquals(7, properties.reconnect().maxAttempts());
                    assertEquals(0.15, properties.reconnect().jitterRatio());
                    assertEquals(12_000, properties.vehicle().serviceIntervalKm());
                    assertEquals(5_000.5, properties.vehicle().initialOdometerKm());
                });
    }

    private static VehicleSimulatorProperties properties(int vehicleCount, Duration sendInterval) {
        return new VehicleSimulatorProperties(
                true, vehicleCount, FLEET_API, GATEWAY, sendInterval,
                Duration.ofSeconds(5), RECONNECT, VEHICLE);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VehicleSimulatorProperties.class)
    static class PropertiesConfiguration {
    }
}
