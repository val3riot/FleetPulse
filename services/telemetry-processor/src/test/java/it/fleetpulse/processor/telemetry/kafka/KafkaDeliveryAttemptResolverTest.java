package it.fleetpulse.processor.telemetry.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaDeliveryAttemptResolverTest {

    private final KafkaDeliveryAttemptResolver resolver =
            new KafkaDeliveryAttemptResolver();

    @Test
    void readsDeliveryAttemptFromKafkaHeader() {
        ConsumerRecord<String, String> record = record();

        record.headers().add(
                KafkaHeaders.DELIVERY_ATTEMPT,
                ByteBuffer.allocate(Integer.BYTES)
                        .putInt(4)
                        .array()
        );

        assertThat(resolver.resolve(record)).isEqualTo(4);
    }

    @Test
    void defaultsToOneWhenHeaderIsMissing() {
        assertThat(resolver.resolve(record())).isEqualTo(1);
    }

    @Test
    void defaultsToOneWhenHeaderIsMalformed() {
        ConsumerRecord<String, String> record = record();

        record.headers().add(
                KafkaHeaders.DELIVERY_ATTEMPT,
                new byte[]{1, 2}
        );

        assertThat(resolver.resolve(record)).isEqualTo(1);
    }

    @Test
    void defaultsToOneWhenAttemptIsNotPositive() {
        ConsumerRecord<String, String> record = record();

        record.headers().add(
                KafkaHeaders.DELIVERY_ATTEMPT,
                ByteBuffer.allocate(Integer.BYTES)
                        .putInt(0)
                        .array()
        );

        assertThat(resolver.resolve(record)).isEqualTo(1);
    }

    private static ConsumerRecord<String, String> record() {
        return new ConsumerRecord<>(
                "telemetry.raw.v1",
                1,
                42L,
                "vehicle-id",
                "payload"
        );
    }
}