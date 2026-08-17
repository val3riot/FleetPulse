package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryDeadLetterEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;

import java.util.Objects;

public final class TelemetryDeadLetterRecoverer implements ConsumerRecordRecoverer {
    private final TelemetryDeadLetterEventFactory eventFactory;
    private final TelemetryTerminalEventPublisher publisher;

    public TelemetryDeadLetterRecoverer(TelemetryDeadLetterEventFactory eventFactory,
        TelemetryTerminalEventPublisher publisher) {
        this.eventFactory = Objects.requireNonNull(eventFactory);
        this.publisher = Objects.requireNonNull(publisher);
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception failure) {
        TelemetryDeadLetterEvent event = eventFactory.create(record, failure);

        publisher.publishDeadLetter(event);
    }
}
