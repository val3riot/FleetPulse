package it.fleetpulse.processor.telemetry.kafka;

import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.serializer
        .DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.Map;
import java.util.Objects;

public final class KafkaOriginalPayloadResolver {

    private static final LogAccessor log =
            new LogAccessor(
                    LogFactory.getLog(
                            KafkaOriginalPayloadResolver.class
                    )
            );

    private final ObjectMapper objectMapper;

    public KafkaOriginalPayloadResolver(
            ObjectMapper objectMapper
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public Map<String, Object> resolve(
            ConsumerRecord<?, ?> record
    ) {
        Objects.requireNonNull(record, "record must not be null");

        if (record.value() != null) {
            return objectMapper.convertValue(
                    record.value(),
                    new TypeReference<Map<String, Object>>() {
                    }
            );
        }

        DeserializationException failure =
                SerializationUtils.getExceptionFromHeader(
                        record,
                        KafkaUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER,
                        log
                );

        if (failure == null || failure.getData() == null) {
            return Map.of();
        }

        return Map.of(
                "rawBase64",
                Base64.getEncoder().encodeToString(
                        failure.getData()
                )
        );
    }
}
