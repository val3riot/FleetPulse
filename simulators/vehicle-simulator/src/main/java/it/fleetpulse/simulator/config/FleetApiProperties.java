package it.fleetpulse.simulator.config;

import java.net.URI;

public record FleetApiProperties(URI baseUrl) {

    public FleetApiProperties {
        if (baseUrl == null) {
            throw new IllegalArgumentException("fleetApi.baseUrl must not be null");
        }
        if (!baseUrl.isAbsolute()) {
            throw new IllegalArgumentException("fleetApi.baseUrl must be absolute");
        }
        String scheme = baseUrl.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("fleetApi.baseUrl must use http or https");
        }
        if (baseUrl.getHost() == null) {
            throw new IllegalArgumentException("fleetApi.baseUrl must contain a host");
        }
    }
}
