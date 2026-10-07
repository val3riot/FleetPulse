package it.fleetpulse.api.dashboard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardConfiguration {
}
