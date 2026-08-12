package it.fleetpulse.simulator.fleet.http;

import it.fleetpulse.simulator.config.VehicleSimulatorProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
class FleetApiClientConfiguration {

    @Bean
    RestClient fleetApiRestClient(VehicleSimulatorProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.fleetApi().baseUrl().toString())
                .build();
    }
}