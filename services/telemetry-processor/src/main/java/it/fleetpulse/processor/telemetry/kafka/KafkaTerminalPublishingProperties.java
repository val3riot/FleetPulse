package it.fleetpulse.processor.telemetry.kafka;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "fleetpulse.kafka.terminal-publication")
@Validated
public record KafkaTerminalPublishingProperties(
        @NotNull Duration confirmationTimeout
) {

    @AssertTrue(message = "confirmation timeout must be positive")
    public boolean isConfirmationTimeoutValid() {
        return confirmationTimeout == null ||
            (!confirmationTimeout.isZero() && !confirmationTimeout.isNegative());
    }
}
