package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.tcp.TelemetryFrameEncoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
class SimulatorComponentConfiguration {

    @Bean
    TelemetryFrameEncoder telemetryFrameEncoder(ObjectMapper objectMapper) {
        return new TelemetryFrameEncoder(objectMapper);
    }
}
