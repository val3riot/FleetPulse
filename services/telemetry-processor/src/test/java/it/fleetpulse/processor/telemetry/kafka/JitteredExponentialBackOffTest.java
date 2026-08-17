package it.fleetpulse.processor.telemetry.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class JitteredExponentialBackOffTest {

    @Test
    void growsExponentiallyStopsAtCapAndLimitsAttempts() {
        JitteredExponentialBackOff backOff =
            new JitteredExponentialBackOff(Duration.ofMillis(500), Duration.ofMillis(1_500), 4, 2.0,
                0.0, () -> 0.5);

        BackOffExecution execution = backOff.start();

        assertThat(execution.nextBackOff()).isEqualTo(500);
        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(1_500);
        assertThat(execution.nextBackOff()).isEqualTo(1_500);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    void appliesDeterministicJitter() {
        JitteredExponentialBackOff minimum =
            new JitteredExponentialBackOff(Duration.ofSeconds(1), Duration.ofSeconds(5), 1, 2.0,
                0.2, () -> 0.0);

        JitteredExponentialBackOff maximum =
            new JitteredExponentialBackOff(Duration.ofSeconds(1), Duration.ofSeconds(5), 1, 2.0,
                0.2, () -> 1.0);

        assertThat(minimum.start().nextBackOff()).isEqualTo(800);
        assertThat(maximum.start().nextBackOff()).isEqualTo(1_200);
    }

    @Test
    void createsIndependentExecutions() {
        JitteredExponentialBackOff backOff =
            new JitteredExponentialBackOff(Duration.ofMillis(250), Duration.ofSeconds(1), 1, 2.0,
                0.0, () -> 0.5);

        assertThat(backOff.start().nextBackOff()).isEqualTo(250);
        assertThat(backOff.start().nextBackOff()).isEqualTo(250);
    }
}
