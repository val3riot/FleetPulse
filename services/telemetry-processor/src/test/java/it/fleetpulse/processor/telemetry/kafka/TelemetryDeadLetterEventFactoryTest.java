package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryDeadLetterEvent;
import it.fleetpulse.processor.telemetry.UnsupportedTelemetryEventVersionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryDeadLetterEventFactoryTest {

    private static final Instant FAILED_AT = Instant.parse("2026-08-17T10:00:00Z");

    private final TelemetryDeadLetterEventFactory factory =
        new TelemetryDeadLetterEventFactory(Clock.fixed(FAILED_AT, ZoneOffset.UTC),
            new KafkaOriginalPayloadResolver(new ObjectMapper()),
            new KafkaDeliveryAttemptResolver());

    @Test
    void createsDeadLetterForUnsupportedVersion() {
        ConsumerRecord<String, Map<String, Object>> record = record();

        TelemetryDeadLetterEvent event = factory.create(record,
            new RuntimeException(new UnsupportedTelemetryEventVersionException(99)));

        assertThat(event.failedAt()).isEqualTo(FAILED_AT);
        assertThat(event.sourceTopic()).isEqualTo("telemetry.raw.v1");
        assertThat(event.sourcePartition()).isEqualTo(1);
        assertThat(event.sourceOffset()).isEqualTo(42L);
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.errorCode()).isEqualTo("UNSUPPORTED_EVENT_VERSION");
        assertThat(event.errorMessage()).isEqualTo("Unsupported telemetry event version: 99");
        assertThat(event.originalKey()).isEqualTo("vehicle-id");
        assertThat(event.originalPayload()).containsEntry("eventVersion", 99);
    }

    @Test
    void createsDeadLetterAfterRetriesAreExhausted() {
        ConsumerRecord<String, Map<String, Object>> record = record();

        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT,
            ByteBuffer.allocate(Integer.BYTES).putInt(4).array());

        TelemetryDeadLetterEvent event =
            factory.create(record, new RuntimeException("database unavailable"));

        assertThat(event.attempts()).isEqualTo(4);
        assertThat(event.errorCode()).isEqualTo("PROCESSING_RETRIES_EXHAUSTED");
        assertThat(event.errorMessage()).isEqualTo("database unavailable");
    }

    private static ConsumerRecord<String, Map<String, Object>> record() {
        return new ConsumerRecord<>("telemetry.raw.v1", 1, 42L, "vehicle-id",
            Map.of("eventVersion", 99, "messageId", "dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"));
    }
}
