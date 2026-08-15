package it.fleetpulse.processor.telemetry.kafka;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fleetpulse.kafka.consumer")
@Validated
public record KafkaConsumerProperties(
        @NotBlank String groupId
) {
}
