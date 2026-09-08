package it.fleetpulse.api.state;

import it.fleetpulse.api.state.redis.LatestStateProjectionProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({VehicleStateProperties.class, LatestStateProjectionProperties.class})
public class VehicleStateConfiguration {
}
