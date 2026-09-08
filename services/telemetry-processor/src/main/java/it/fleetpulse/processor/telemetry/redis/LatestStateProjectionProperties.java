package it.fleetpulse.processor.telemetry.redis;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@ConfigurationProperties(prefix = "fleetpulse.telemetry.latest-state")
@Validated
public record LatestStateProjectionProperties(
        @NotNull @DurationMin (millis = 1) Duration ttl,
        @Min(1) int maxAttempts
) {

}
