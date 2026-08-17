package it.fleetpulse.processor.telemetry.kafka;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "fleetpulse.kafka.consumer")
@Validated
public record KafkaConsumerProperties(
        @NotBlank String groupId,
        @Min(0) int retryMaxAttempts,
        @NotNull Duration retryInitialBackoff,
        @NotNull Duration retryMaxBackoff,
        @DecimalMin("1.0") double retryMultiplier,
        @DecimalMin("0.0") @DecimalMax("1.0") double retryJitterRatio
) {
    @AssertTrue(message = "retry backoff durations must be positive and initial backoff must not " +
        "exceed max backoff")
    public boolean isRetryBackoffValid() {
        if (retryInitialBackoff == null || retryMaxBackoff == null) {
            return true;
        }

        return !retryInitialBackoff.isNegative() && !retryInitialBackoff.isZero() &&
            !retryMaxBackoff.isNegative() && !retryMaxBackoff.isZero() &&
            retryInitialBackoff.compareTo(retryMaxBackoff) <= 0;
    }
}
