package it.fleetpulse.api.state.redis;

import java.time.Duration;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@ConfigurationProperties(prefix = "fleetpulse.api.state.cache")
@Validated
public record LatestStateProjectionProperties(
        @NotNull @DurationMin(millis = 1) Duration ttl,
        @Min(1) int maxAttempts
) {

}
