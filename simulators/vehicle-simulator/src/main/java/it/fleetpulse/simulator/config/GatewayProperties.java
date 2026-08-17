package it.fleetpulse.simulator.config;

import java.time.Duration;

public record GatewayProperties(
        String host,
        int port,
        Duration connectTimeout
) {

    public GatewayProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("gateway.host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("gateway.port must be between 1 and 65535");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("gateway.connectTimeout must be greater than zero");
        }
        if (connectTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("gateway.connectTimeout is too large");
        }
    }
}
