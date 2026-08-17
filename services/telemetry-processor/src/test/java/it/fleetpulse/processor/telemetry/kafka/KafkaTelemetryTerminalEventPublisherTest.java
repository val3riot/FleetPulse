package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryDeadLetterEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaTelemetryTerminalEventPublisherTest {

    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");

    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    private final KafkaTemplate<String, Object> kafkaTemplate = kafkaTemplate();

    private final KafkaTelemetryTerminalEventPublisher publisher =
        new KafkaTelemetryTerminalEventPublisher(kafkaTemplate,
            new KafkaTopicsProperties("telemetry.raw.v1", "telemetry.rejected.v1",
                "telemetry.dead-letter.v1"),
            new KafkaTerminalPublishingProperties(Duration.ofSeconds(1)));

    @SuppressWarnings("unchecked")
    private static KafkaTemplate<String, Object> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }

    @Test
    void publishesRejectionUsingVehicleIdAsKey() {
        TelemetryRejectedEvent event = new TelemetryRejectedEvent(MESSAGE_ID, VEHICLE_ID,
            TelemetryRejectionReason.UNKNOWN_VEHICLE, Instant.parse("2026-08-17T10:00:00Z"),
            "telemetry.raw.v1", 1, 42L);

        when(kafkaTemplate.send("telemetry.rejected.v1", VEHICLE_ID.toString(), event)).thenReturn(
            CompletableFuture.completedFuture(null));

        publisher.publishRejected(event);

        verify(kafkaTemplate).send("telemetry.rejected.v1", VEHICLE_ID.toString(), event);
    }

    @Test
    void publishesDeadLetterUsingOriginalKey() {
        TelemetryDeadLetterEvent event =
            new TelemetryDeadLetterEvent(Instant.parse("2026-08-17T10:00:00Z"), "telemetry.raw.v1",
                1, 42L, 4, "DATABASE_UNAVAILABLE", "Database unavailable", VEHICLE_ID.toString(),
                Map.of("messageId", MESSAGE_ID.toString()));

        when(kafkaTemplate.send("telemetry.dead-letter.v1", VEHICLE_ID.toString(),
            event)).thenReturn(CompletableFuture.completedFuture(null));

        publisher.publishDeadLetter(event);

        verify(kafkaTemplate).send("telemetry.dead-letter.v1", VEHICLE_ID.toString(), event);
    }

    @Test
    void propagatesBrokerPublicationFailure() {
        TelemetryDeadLetterEvent event =
            new TelemetryDeadLetterEvent(Instant.parse("2026-08-17T10:00:00Z"), "telemetry.raw.v1",
                1, 42L, 4, "DATABASE_UNAVAILABLE", "Database unavailable", VEHICLE_ID.toString(),
                Map.of());

        RuntimeException brokerFailure = new RuntimeException("broker unavailable");

        when(kafkaTemplate.send("telemetry.dead-letter.v1", VEHICLE_ID.toString(),
            event)).thenReturn(CompletableFuture.failedFuture(brokerFailure));

        TelemetryTerminalPublicationException thrown =
            assertThrows(TelemetryTerminalPublicationException.class,
                () -> publisher.publishDeadLetter(event));

        assertSame(brokerFailure, thrown.getCause().getCause());
    }
}
