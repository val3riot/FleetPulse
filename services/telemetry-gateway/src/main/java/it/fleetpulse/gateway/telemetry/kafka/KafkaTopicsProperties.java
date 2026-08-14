package it.fleetpulse.gateway.telemetry.kafka;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fleetpulse.kafka.topics")
@Validated
public record KafkaTopicsProperties(
        @NotBlank String raw
){
}
