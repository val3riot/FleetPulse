package it.fleetpulse.gateway.tcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "gateway.tcp")
public record TcpServerProperties(@DefaultValue("false") boolean enabled, @DefaultValue("7000") int port) {

    public TcpServerProperties {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
    }
}
