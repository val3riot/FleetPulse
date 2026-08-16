package it.fleetpulse.processor.telemetry.kafka;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public final class JitteredExponentialBackOff implements BackOff {

    private final long initialIntervalMillis;
    private final long maxIntervalMillis;
    private final int maxAttempts;
    private final double multiplier;
    private final double jitterRatio;
    private final DoubleSupplier random;

    public JitteredExponentialBackOff(
            Duration initialInterval,
            Duration maxInterval,
            int maxAttempts,
            double multiplier,
            double jitterRatio
    ) {
        this(
                initialInterval,
                maxInterval,
                maxAttempts,
                multiplier,
                jitterRatio,
                () -> ThreadLocalRandom.current().nextDouble()
        );
    }

    JitteredExponentialBackOff(
            Duration initialInterval,
            Duration maxInterval,
            int maxAttempts,
            double multiplier,
            double jitterRatio,
            DoubleSupplier random
    ) {
        Objects.requireNonNull(initialInterval);
        Objects.requireNonNull(maxInterval);
        this.random = Objects.requireNonNull(random);

        this.initialIntervalMillis = initialInterval.toMillis();
        this.maxIntervalMillis = maxInterval.toMillis();
        this.maxAttempts = maxAttempts;
        this.multiplier = multiplier;
        this.jitterRatio = jitterRatio;
    }

    @Override
    public BackOffExecution start() {
        return new Execution();
    }

    private final class Execution implements BackOffExecution {

        private int attempts;
        private double nextInterval = initialIntervalMillis;

        @Override
        public long nextBackOff() {
            if (attempts >= maxAttempts) {
                return STOP;
            }

            attempts++;

            double cappedInterval =
                    Math.min(nextInterval, maxIntervalMillis);

            nextInterval = Math.min(
                    nextInterval * multiplier,
                    maxIntervalMillis
            );

            double jitter =
                    (random.getAsDouble() * 2.0 - 1.0)
                            * jitterRatio;

            return Math.max(
                    0L,
                    Math.round(cappedInterval * (1.0 + jitter))
            );
        }
    }
}
