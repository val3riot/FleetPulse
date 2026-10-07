package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRetryObservabilityTest {

    @Test
    void recordsFailedDeliveriesAndTerminalFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaRetryObservability observability = new KafkaRetryObservability(registry);
        ConsumerRecord<String, String> record =
            new ConsumerRecord<>("telemetry.raw.v1", 1, 42L, "vehicle-id", "payload");
        RuntimeException failure = new RuntimeException("database unavailable");

        observability.failedDelivery(record, failure, 1);
        observability.failedDelivery(record, failure, 2);
        observability.recovered(record, failure);

        assertThat(registry.get("fleetpulse.processor.failures").counter().count()).isEqualTo(2.0);
        assertThat(
            registry.get("fleetpulse.processor.failures.terminal").counter().count()).isEqualTo(
            1.0);
        assertThat(registry.get("fleetpulse.processor.dead.letter").counter().count()).isEqualTo(
            1.0);
    }
    @Test
    void excludesRawPayloadAndExceptionMessageFromFailureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(KafkaRetryObservability.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var observability = new KafkaRetryObservability(new SimpleMeterRegistry());
            var record = new ConsumerRecord<>("telemetry.raw.v1", 1, 42L,
                "private-key", "private-payload");
            var failure = new RuntimeException("private-credentials");
            observability.failedDelivery(record, failure, 1);
            observability.recovered(record, failure);
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage())
                    .doesNotContain("private-key", "private-payload", "private-credentials");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("errorType");
                    assertThat(pair.value).isEqualTo("RuntimeException");
                });
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

}
