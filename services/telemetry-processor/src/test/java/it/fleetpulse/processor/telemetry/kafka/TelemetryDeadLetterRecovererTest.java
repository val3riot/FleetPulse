package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryDeadLetterEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryDeadLetterRecovererTest {

    private final TelemetryDeadLetterEventFactory eventFactory =
        mock(TelemetryDeadLetterEventFactory.class);

    private final TelemetryTerminalEventPublisher publisher =
        mock(TelemetryTerminalEventPublisher.class);

    private final TelemetryDeadLetterRecoverer recoverer =
        new TelemetryDeadLetterRecoverer(eventFactory, publisher);

    @Test
    void createsAndPublishesDeadLetterEvent() {
        ConsumerRecord<String, String> record = record();
        RuntimeException failure = new RuntimeException("processing failed");
        TelemetryDeadLetterEvent event = deadLetterEvent();

        when(eventFactory.create(record, failure)).thenReturn(event);

        recoverer.accept(record, failure);

        verify(eventFactory).create(record, failure);
        verify(publisher).publishDeadLetter(event);
    }

    @Test
    void propagatesTerminalPublicationFailure() {
        ConsumerRecord<String, String> record = record();
        RuntimeException processingFailure = new RuntimeException("processing failed");
        RuntimeException publicationCause = new RuntimeException("broker unavailable");
        TelemetryTerminalPublicationException publicationFailure =
            new TelemetryTerminalPublicationException("Terminal publication failed",
                publicationCause);
        TelemetryDeadLetterEvent event = deadLetterEvent();

        when(eventFactory.create(record, processingFailure)).thenReturn(event);

        org.mockito.Mockito.doThrow(publicationFailure).when(publisher).publishDeadLetter(event);

        TelemetryTerminalPublicationException thrown =
            assertThrows(TelemetryTerminalPublicationException.class,
                () -> recoverer.accept(record, processingFailure));

        assertSame(publicationFailure, thrown);
    }

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>("telemetry.raw.v1", 1, 42L, "vehicle-id", "payload");
    }

    private static TelemetryDeadLetterEvent deadLetterEvent() {
        return new TelemetryDeadLetterEvent(Instant.parse("2026-08-17T10:00:00Z"),
            "telemetry.raw.v1", 1, 42L, 4, "PROCESSING_RETRIES_EXHAUSTED", "processing failed",
            "vehicle-id", Map.of());
    }
}
