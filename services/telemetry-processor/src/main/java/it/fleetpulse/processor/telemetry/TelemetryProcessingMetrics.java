package it.fleetpulse.processor.telemetry;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public final class TelemetryProcessingMetrics {
    public enum Outcome { PERSISTED, DUPLICATE, REJECTED, FAILED }

    private final MeterRegistry registry;
    private final Counter attempts;
    private final Counter persisted;
    private final Counter duplicates;
    private final Map<Outcome, Timer> processing = new EnumMap<>(Outcome.class);
    private final Timer completedProjection;
    private final Timer failedProjection;

    public TelemetryProcessingMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        attempts = registry.counter("fleetpulse.processor.events");
        persisted = registry.counter("fleetpulse.processor.persisted");
        duplicates = registry.counter("fleetpulse.processor.duplicates");
        for (Outcome outcome : Outcome.values()) {
            processing.put(outcome, Timer.builder("fleetpulse.processing.latency")
                .description("Decoded telemetry attempt through aggregate commit, excluding Redis")
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

    public void completeAttempt(Timer.Sample sample, Outcome outcome) {
        sample.stop(processing.get(outcome));
        switch (outcome) {
            case PERSISTED -> persisted.increment();
            case DUPLICATE -> duplicates.increment();
            default -> { }
        }
    }

    public Timer.Sample startProjection() {
        return Timer.start(registry);
    }

    public void completeProjection(Timer.Sample sample, boolean failed) {
        sample.stop(failed ? failedProjection : completedProjection);
    }
}
