package it.fleetpulse.simulator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "simulator")
public record VehicleSimulatorProperties(
        boolean enabled,
        int vehicleCount,
        FleetApiProperties fleetApi,
        GatewayProperties gateway,
        Duration sendInterval,
        Duration shutdownGracePeriod,
        ReconnectProperties reconnect,
        VehicleProperties vehicle
) {
    public VehicleSimulatorProperties {
        requireConfigured(fleetApi, "fleetApi");
        requireConfigured(gateway, "gateway");
        requireConfigured(reconnect, "reconnect");
        requireConfigured(vehicle, "vehicle");
        if (vehicleCount <= 0) {
            throw new IllegalArgumentException("vehicleCount must be greater than zero");
        }
        if (sendInterval == null || sendInterval.isZero() || sendInterval.isNegative()) {
            throw new IllegalArgumentException("sendInterval must be greater than zero");
        }
        if (shutdownGracePeriod == null || shutdownGracePeriod.isZero() ||
            shutdownGracePeriod.isNegative()) {
            throw new IllegalArgumentException("shutdownGracePeriod must be greater than zero");
        }
    }

    private static void requireConfigured(Object value, String property) {
        if (value == null) {
            throw new IllegalArgumentException(property + " must be configured");
        }
    }
}
