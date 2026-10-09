package it.fleetpulse.processor.telemetry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public final class TelemetryProcessingMetrics {
    public enum Outcome {
        PERSISTED, DUPLICATE, REJECTED, FAILED
    }

    private final MeterRegistry registry;
    private final Counter attempts;
    private final Counter persisted;
    private final Counter duplicates;
    private final Map<Outcome, Timer> processing = new EnumMap<>(Outcome.class);
    private final Timer completedProjection;
    private final Timer failedProjection;
    private final Timer persistence;
    private final Counter invalidPersistenceClock;

    public TelemetryProcessingMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        attempts = registry.counter("fleetpulse.processor.events");
        persisted = registry.counter("fleetpulse.processor.persisted");
        duplicates = registry.counter("fleetpulse.processor.duplicates");
        persistence = Timer.builder("fleetpulse.pipeline.persistence.latency")
                .description(
                        "Gateway receivedAt through successful aggregate commit, excluding Redis")
                .register(registry);
        invalidPersistenceClock = registry.counter("fleetpulse.pipeline.persistence.clock.invalid");
        for (Outcome outcome : Outcome.values()) {
            processing.put(outcome, Timer.builder("fleetpulse.processing.latency")
                    .description(
                            "Decoded telemetry attempt through aggregate commit, excluding Redis")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
        }
        completedProjection = Timer.builder("fleetpulse.processor.projection.latency")
                .description("Latest-state Redis update duration after aggregate commit")
                .tag("outcome", "completed").register(registry);
        failedProjection = Timer.builder("fleetpulse.processor.projection.latency")
                .description("Latest-state Redis update duration after aggregate commit")
                .tag("outcome", "failed").register(registry);
    }

    public Timer.Sample startAttempt() {
        attempts.increment();
        return Timer.start(registry);
    }

    /** Returns null for clock skew; invalid durations never enter the histogram. */
    public Duration recordPersistence(Instant receivedAt, Instant committedAt) {
        Duration duration = Duration.between(receivedAt, committedAt);
        if (duration.isNegative()) {
            invalidPersistenceClock.increment();
            return null;
        }
        persistence.record(duration);
        return duration;
    }

    public void completeAttempt(Timer.Sample sample, Outcome outcome) {
        sample.stop(processing.get(outcome));
        switch (outcome) {
            case PERSISTED -> persisted.increment();
            case DUPLICATE -> duplicates.increment();
            default -> {
            }
        }
    }

    public Timer.Sample startProjection() {
        return Timer.start(registry);
    }

    public void completeProjection(Timer.Sample sample, boolean failed) {
        sample.stop(failed ? failedProjection : completedProjection);
    }
}
