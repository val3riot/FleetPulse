package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryDeadLetterEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


public final class KafkaTelemetryTerminalEventPublisher implements TelemetryTerminalEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final KafkaTopicsProperties topics;
    private final Duration confirmationTimeout;

    public KafkaTelemetryTerminalEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            KafkaTopicsProperties topics,
            KafkaTerminalPublishingProperties properties
    ) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate);
        this.topics = Objects.requireNonNull(topics);
        this.confirmationTimeout = Objects.requireNonNull(
                properties
        ).confirmationTimeout();
    }

    @Override
    public void publishRejected(TelemetryRejectedEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        publish(
                topics.rejected(),
                event.vehicleId().toString(),
                event
        );
    }

    @Override
    public void publishDeadLetter(TelemetryDeadLetterEvent event) {
        Objects.requireNonNull(event, "event must not be null");

        publish(
                topics.deadLetter(),
                event.originalKey(),
                event
        );
    }

    private void publish(String topic, String key, Object event) {
        try {
            kafkaTemplate.send(topic, key, event)
                    .get(
                            confirmationTimeout.toMillis(),
                            TimeUnit.MILLISECONDS
                    );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw publicationFailure(
                    topic,
                    exception
            );
        } catch (
                ExecutionException
                | TimeoutException exception
        ) {
            throw publicationFailure(
                    topic,
                    exception
            );
        }
    }

    private static TelemetryTerminalPublicationException
    publicationFailure(
            String topic,
            Exception cause
    ) {
        return new TelemetryTerminalPublicationException(
                "Terminal event publication failed: topic=" + topic, cause);
    }
}
