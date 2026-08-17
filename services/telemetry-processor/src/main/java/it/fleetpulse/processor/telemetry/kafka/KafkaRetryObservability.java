package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.RetryListener;

import java.util.Objects;

public final class KafkaRetryObservability implements RetryListener {
    private static final Logger log =
            LoggerFactory.getLogger(KafkaRetryObservability.class);

    private final Counter failedDeliveries;
    private final Counter terminalFailures;
    private final Counter deadLetters;

    public KafkaRetryObservability(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");

        failedDeliveries = Counter.builder(
                        "fleetpulse.processor.failures"
                )
                .description(
                        "Telemetry processing delivery failures"
                )
                .register(registry);

        terminalFailures = Counter.builder(
                        "fleetpulse.processor.failures.terminal"
                )
                .description(
                        "Telemetry processing failures not recovered "
                                + "by retry"
                )
                .register(registry);

        deadLetters = Counter.builder(
                        "fleetpulse.processor.dead.letter"
                )
                .description(
                        "Telemetry records published to the dead-letter topic"
                )
                .register(registry);
    }

    @Override
    public void failedDelivery(
            ConsumerRecord<?, ?> record,
            Exception failure,
            int deliveryAttempt
    ) {
        failedDeliveries.increment();

        log.warn(
                "Kafka telemetry processing failed: "
                        + "topic={}, partition={}, offset={}, "
                        + "deliveryAttempt={}, errorType={}, message={}",
                record.topic(),
                record.partition(),
                record.offset(),
                deliveryAttempt,
                failure.getClass().getSimpleName(),
                failure.getMessage()
        );
    }

    @Override
    public void recovered(
            ConsumerRecord<?, ?> record,
            Exception failure
    ) {
        terminalFailures.increment();
        deadLetters.increment();

        log.error(
                "Kafka telemetry processing reached terminal handling: "
                        + "topic={}, partition={}, offset={}, "
                        + "errorType={}, message={}",
                record.topic(),
                record.partition(),
                record.offset(),
                failure.getClass().getSimpleName(),
                failure.getMessage()
        );
    }
}
