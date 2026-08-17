package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRetryObservabilityTest {

    @Test
    void recordsFailedDeliveriesAndTerminalFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaRetryObservability observability =
                new KafkaRetryObservability(registry);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(
                        "telemetry.raw.v1",
                        1,
                        42L,
                        "vehicle-id",
                        "payload"
                );
        RuntimeException failure =
                new RuntimeException("database unavailable");

        observability.failedDelivery(record, failure, 1);
        observability.failedDelivery(record, failure, 2);
        observability.recovered(record, failure);

        assertThat(registry.get(
                "fleetpulse.processor.failures"
        ).counter().count()).isEqualTo(2.0);
        assertThat(registry.get(
                "fleetpulse.processor.failures.terminal"
        ).counter().count()).isEqualTo(1.0);
        assertThat(registry.get(
                "fleetpulse.processor.dead.letter"
        ).counter().count()).isEqualTo(1.0);
    }
}
