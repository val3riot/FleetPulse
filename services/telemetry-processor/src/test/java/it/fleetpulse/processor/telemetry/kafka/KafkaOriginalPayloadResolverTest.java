package it.fleetpulse.processor.telemetry.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.serializer.SerializationUtils;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaOriginalPayloadResolverTest {

    private final KafkaOriginalPayloadResolver resolver =
        new KafkaOriginalPayloadResolver(new ObjectMapper());

    @Test
    void convertsDeserializedValueToMap() {
        ConsumerRecord<String, Map<String, Object>> record =
            new ConsumerRecord<>("telemetry.raw.v1", 1, 42L, "vehicle-id",
                Map.of("eventVersion", 1));

        assertThat(resolver.resolve(record)).containsEntry("eventVersion", 1);
    }

    @Test
    void preservesUndeserializableBytesAsBase64() {
        byte[] rawPayload = "{\"eventVersion\":".getBytes(StandardCharsets.UTF_8);
        ConsumerRecord<String, Object> record =
            new ConsumerRecord<>("telemetry.raw.v1", 1, 42L, "vehicle-id", null);

        SerializationUtils.deserializationException(record.headers(), rawPayload,
            new IllegalArgumentException("invalid JSON"), false);

        assertThat(resolver.resolve(record)).containsEntry("rawBase64",
            Base64.getEncoder().encodeToString(rawPayload));
    }

    @Test
    void returnsEmptyPayloadWhenNoValueOrFailureHeaderExists() {
        ConsumerRecord<String, Object> record =
            new ConsumerRecord<>("telemetry.raw.v1", 1, 42L, "vehicle-id", null);

        assertThat(resolver.resolve(record)).isEmpty();
    }
}
