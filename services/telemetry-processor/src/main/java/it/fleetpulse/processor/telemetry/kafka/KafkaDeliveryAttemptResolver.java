package it.fleetpulse.processor.telemetry.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class KafkaDeliveryAttemptResolver {

    public int resolve(ConsumerRecord<?, ?> record) {
        Objects.requireNonNull(record, "record must not be null");

        Header header = record.headers().lastHeader(
                KafkaHeaders.DELIVERY_ATTEMPT
        );

        if (header == null || header.value() == null) {
            return 1;
        }

        byte[] value = header.value();

        if (value.length != Integer.BYTES) {
            return 1;
        }

        int attempt = ByteBuffer.wrap(value).getInt();

        return Math.max(attempt, 1);
    }
}
