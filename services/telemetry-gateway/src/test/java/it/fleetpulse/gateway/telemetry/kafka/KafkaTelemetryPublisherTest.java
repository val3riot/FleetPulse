package it.fleetpulse.gateway.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@ExtendWith(MockitoExtension.class)
public class KafkaTelemetryPublisherTest {
    private static final String TOPIC = "telemetry.raw.v1";
    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    @Mock
    private KafkaTemplate<String, TelemetryEvent> kafkaTemplate;

    @Test
    void publishesEventUsingVehicleIdAsKey() {
        TelemetryEvent event = event();
        when(kafkaTemplate.send(TOPIC, VEHICLE_ID.toString(), event)).thenReturn(
            CompletableFuture.completedFuture(null));
        KafkaTelemetryPublisher publisher = new KafkaTelemetryPublisher(kafkaTemplate, TOPIC);
        publisher.publish(event).toCompletableFuture().join();
        verify(kafkaTemplate).send(TOPIC, VEHICLE_ID.toString(), event);
    }

    @Test
    void propagatesAsynchronousKafkaFailure() {
        TelemetryEvent event = event();
        RuntimeException kafkaFailure = new RuntimeException("Kafka unavailable");
        when(kafkaTemplate.send(TOPIC, VEHICLE_ID.toString(), event)).thenReturn(
            CompletableFuture.failedFuture(kafkaFailure));
        KafkaTelemetryPublisher publisher = new KafkaTelemetryPublisher(kafkaTemplate, TOPIC);
        CompletionException exception = assertThrows(CompletionException.class,
            () -> publisher.publish(event).toCompletableFuture().join());

        assertSame(kafkaFailure, exception.getCause());
    }

    private static TelemetryEvent event() {
        return new TelemetryEvent(1, UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            VEHICLE_ID, 42, Instant.parse("2026-08-01T10:15:30Z"),
            Instant.parse("2026-08-01T10:15:30.083Z"),
            new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964));
    }
}
