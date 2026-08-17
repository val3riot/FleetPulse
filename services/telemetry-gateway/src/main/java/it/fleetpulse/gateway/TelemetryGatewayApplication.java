package it.fleetpulse.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TelemetryGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(TelemetryGatewayApplication.class, args);
    }

}
