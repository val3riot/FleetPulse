package it.fleetpulse.gateway.tcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gateway.tcp")
public record TcpServerProperties(@DefaultValue("false") boolean enabled, @DefaultValue("7000") int port, @DefaultValue("100") int maxConnections) {

    public TcpServerProperties {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections must be greater than zero");
        }
    }
}
