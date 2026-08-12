package it.fleetpulse.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class VehicleSimulatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(VehicleSimulatorApplication.class, args);
	}

}
