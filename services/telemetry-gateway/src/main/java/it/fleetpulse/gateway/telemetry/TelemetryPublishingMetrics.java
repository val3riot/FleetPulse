package it.fleetpulse.gateway.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Objects;

public final class TelemetryPublishingMetrics {
    private final MeterRegistry registry;
    private final Counter publishFailures;
    private final Timer acknowledgementLatency;
    private final Timer confirmedPublication;
    private final Timer failedPublication;

    public TelemetryPublishingMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");

        this.publishFailures = Counter.builder("fleetpulse.gateway.publish.failures")
                .description("Kafka publications not confirmed by the gateway").register(registry);

        this.acknowledgementLatency = Timer.builder("fleetpulse.gateway.ack.latency")
                .description("ACK decision preparation, excluding socket write").register(registry);
        confirmedPublication = Timer.builder("fleetpulse.gateway.publish.latency")
                .description("Kafka publication until confirmation or failure")
                .tag("outcome", "confirmed").register(registry);
        failedPublication = Timer.builder("fleetpulse.gateway.publish.latency")
                .description("Kafka publication until confirmation or failure")
                .tag("outcome", "failed").register(registry);
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

    Timer.Sample startPublication() {
        return Timer.start(registry);
    }

    void completePublication(Timer.Sample sample, boolean confirmed) {
        sample.stop(confirmed ? confirmedPublication : failedPublication);
    }
}
