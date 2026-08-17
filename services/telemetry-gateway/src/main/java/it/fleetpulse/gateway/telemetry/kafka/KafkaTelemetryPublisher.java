package it.fleetpulse.gateway.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.gateway.telemetry.TelemetryPublisher;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public final class KafkaTelemetryPublisher implements TelemetryPublisher {
    private final KafkaTemplate<String, TelemetryEvent> kafkaTemplate;
    private final String topic;

    public KafkaTelemetryPublisher(KafkaTemplate<String, TelemetryEvent> kafkaTemplate,
        String topic) {
        this.kafkaTemplate =
            Objects.requireNonNull(kafkaTemplate, "kafkaTemplate must not be null");
        this.topic = Objects.requireNonNull(topic, "topic must not be null");
    }

    @Override
    public CompletionStage<Void> publish(TelemetryEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        String key = event.vehicleId().toString();
        return kafkaTemplate.send(topic, key, event).thenApply(sendResult -> null);
    }
}
