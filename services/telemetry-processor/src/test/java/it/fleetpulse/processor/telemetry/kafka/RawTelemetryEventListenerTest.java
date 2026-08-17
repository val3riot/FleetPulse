package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RawTelemetryEventListenerTest {
    @Test
    void delegatesRecordValueToHandler() {
        AtomicReference<TelemetryEvent> handledEvent = new AtomicReference<>();
        RawTelemetryEventListener listener = new RawTelemetryEventListener(
                (event, source) -> handledEvent.set(event)
        );

        TelemetryEvent event = event(TelemetryEventVersions.V1);

        listener.onTelemetry(record(event));

        assertSame(event, handledEvent.get());
    }

    @Test
    void propagatesHandlerFailure() {
        RuntimeException failure = new RuntimeException("processing failed");
        RawTelemetryEventListener listener =
                new RawTelemetryEventListener((event, source) -> {
                    throw failure;
                });

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> listener.onTelemetry(record(event(TelemetryEventVersions.V1)))
        );

        assertSame(failure, thrown);
    }

    private ConsumerRecord<String, TelemetryEvent> record(TelemetryEvent event) {
        return new ConsumerRecord<>(
                "telemetry.raw.v1",
                1,
                42L,
                event.vehicleId().toString(),
                event
        );
    }
    private static TelemetryEvent event(int version) {
        return new TelemetryEvent(
                version,
                UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                42,
                Instant.parse("2026-08-01T10:15:30Z"),
                Instant.parse("2026-08-01T10:15:30.083Z"),
                new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964)
        );
    }
}
