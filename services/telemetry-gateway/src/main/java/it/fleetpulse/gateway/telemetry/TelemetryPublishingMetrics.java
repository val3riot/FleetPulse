package it.fleetpulse.gateway.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

public final class TelemetryPublishingMetrics {
    private final MeterRegistry registry;
    private final Counter publishFailures;
    private final Timer acknowledgementLatency;

    public TelemetryPublishingMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        this.publishFailures = Counter.builder("fleetpulse.gateway.publish.failures")
            .description("Kafka publications not confirmed by the gateway").register(registry);

        this.acknowledgementLatency = Timer.builder("fleetpulse.gateway.ack.latency")
            .description("Time required to produce a TCP acknowledgement").register(registry);
    }

    Timer.Sample startAcknowledgement() {
        return Timer.start(registry);
    }

    void publicationFailed() {
        publishFailures.increment();
    }

    void completeAcknowledgement(Timer.Sample sample) {
        Objects.requireNonNull(sample, "sample must not be null");
        sample.stop(acknowledgementLatency);
    }
}
