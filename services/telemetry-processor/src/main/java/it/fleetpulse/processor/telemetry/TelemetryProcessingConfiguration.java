package it.fleetpulse.processor.telemetry;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TelemetryProcessingConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
