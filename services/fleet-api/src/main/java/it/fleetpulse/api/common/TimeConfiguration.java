package it.fleetpulse.api.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TimeConfiguration {

    /**
     * Espone il clock UTC usato per generare timestamp coerenti.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
