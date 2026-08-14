package it.fleetpulse.gateway.telemetry.kafka;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "fleetpulse.kafka.publisher")
@Validated
public record KafkaPublisherProperties(
        @NotNull @DurationMin(millis = 1) Duration confirmationTimeout
){
}

