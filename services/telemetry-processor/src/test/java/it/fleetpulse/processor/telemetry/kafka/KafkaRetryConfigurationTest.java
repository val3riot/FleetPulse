package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.processor.telemetry.UnsupportedTelemetryEventVersionException;
import org.apache.kafka.common.errors.RetriableException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class KafkaRetryConfigurationTest {

    private final KafkaRetryConfiguration configuration =
            new KafkaRetryConfiguration();

    @Test
    void classifiesTemporaryInfrastructureFailuresAsRetryable() {
        DefaultErrorHandler errorHandler = errorHandler();

        assertThat(errorHandler.removeClassification(
                TransientDataAccessException.class
        )).isTrue();
        assertThat(errorHandler.removeClassification(
                RecoverableDataAccessException.class
        )).isTrue();
        assertThat(errorHandler.removeClassification(
                DataAccessResourceFailureException.class
        )).isTrue();
        assertThat(errorHandler.removeClassification(
                RetriableException.class
        )).isTrue();
        assertThat(errorHandler.removeClassification(
                TelemetryTerminalPublicationException.class
        )).isTrue();
    }

    @Test
    void classifiesPermanentFailuresAsNotRetryable() {
        DefaultErrorHandler errorHandler = errorHandler();

        assertThat(errorHandler.removeClassification(
                UnsupportedTelemetryEventVersionException.class
        )).isFalse();
        assertThat(errorHandler.removeClassification(
                DataIntegrityViolationException.class
        )).isFalse();
    }

    private DefaultErrorHandler errorHandler() {
        KafkaConsumerProperties properties =
                new KafkaConsumerProperties(
                        "test-group",
                        3,
                        Duration.ofMillis(500),
                        Duration.ofSeconds(5),
                        2.0,
                        0.2
                );

        KafkaRetryObservability observability =
                new KafkaRetryObservability(
                        new SimpleMeterRegistry()
                );
        ConsumerRecordRecoverer recoverer =
                mock(ConsumerRecordRecoverer.class);
        return configuration.kafkaErrorHandler(
                properties,
                observability,
                recoverer
        );
    }
}
