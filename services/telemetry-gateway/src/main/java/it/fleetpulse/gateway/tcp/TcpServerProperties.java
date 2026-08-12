package it.fleetpulse.gateway.tcp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "gateway.tcp")
public record TcpServerProperties(@DefaultValue("false") boolean enabled,
                                  @DefaultValue("7000") int port,
                                  @DefaultValue("100") int maxConnections,
                                  @DefaultValue("30s") Duration readTimeout,
                                  @DefaultValue("5s") Duration shutdownGracePeriod) {

    public TcpServerProperties {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        if (maxConnections <= 0) {
            throw new IllegalArgumentException("maxConnections must be greater than zero");
        }
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be greater than zero");
        }
        if (readTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("readTimeout is too large");
        }
        if (shutdownGracePeriod.isNegative() || shutdownGracePeriod.isZero()) {
            throw new IllegalArgumentException("shutdownGracePeriod must be greater than zero");
        }
    }
}
