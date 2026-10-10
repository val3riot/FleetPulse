package it.fleetpulse.gateway.telemetry.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;

import java.time.Duration;
import java.util.Map;

/** Ensures the configured producer waits fit inside the gateway decision budget. */
public final class KafkaSendBudget {
    private KafkaSendBudget() {
    }

    public static void validate(Map<String, Object> configuration, Duration responseBudget) {
        long budget = responseBudget.toMillis();
        long block = value(configuration, ProducerConfig.MAX_BLOCK_MS_CONFIG);
        long request = value(configuration, ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG);
        long delivery = value(configuration, ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
        long linger = value(configuration, ProducerConfig.LINGER_MS_CONFIG);
        if (block <= 0 || request <= 0 || delivery <= 0 || linger < 0
                || block >= budget || delivery > budget - block
                || linger > delivery || request > delivery - linger) {
            throw new IllegalArgumentException("Kafka producer requires positive timeouts, "
                    + "request.timeout.ms + linger.ms <= delivery.timeout.ms and "
                    + "max.block.ms + delivery.timeout.ms <= confirmation-timeout");
        }
    }

    private static long value(Map<String, Object> configuration, String key) {
        Object value = configuration.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Explicit Kafka producer setting required: " + key);
        }
        return Long.parseLong(value.toString());
    }
}
