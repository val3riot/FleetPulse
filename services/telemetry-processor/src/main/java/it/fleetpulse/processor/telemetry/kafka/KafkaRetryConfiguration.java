package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.processor.telemetry.UnsupportedTelemetryEventVersionException;
import org.apache.kafka.common.errors.RetriableException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

@Configuration(proxyBeanMethods = false)
public class KafkaRetryConfiguration {

    @Bean
    DefaultErrorHandler kafkaErrorHandler(
            KafkaConsumerProperties properties,
            KafkaRetryObservability observability,
            ConsumerRecordRecoverer recoverer
    ) {
        JitteredExponentialBackOff backOff =
                new JitteredExponentialBackOff(
                        properties.retryInitialBackoff(),
                        properties.retryMaxBackoff(),
                        properties.retryMaxAttempts(),
                        properties.retryMultiplier(),
                        properties.retryJitterRatio()
                );

        DefaultErrorHandler errorHandler =
                new DefaultErrorHandler(
                        recoverer,
                        backOff
                );

        errorHandler.defaultFalse(true);
        errorHandler.addRetryableExceptions(
                TransientDataAccessException.class,
                RecoverableDataAccessException.class,
                DataAccessResourceFailureException.class,
                RetriableException.class,
                TelemetryTerminalPublicationException.class
        );
        errorHandler.addNotRetryableExceptions(
                UnsupportedTelemetryEventVersionException.class,
                DataIntegrityViolationException.class
        );
        errorHandler.setRetryListeners(observability);
        return errorHandler;
    }

    @Bean
    KafkaRetryObservability kafkaRetryObservability(
            MeterRegistry registry
    ) {
        return new KafkaRetryObservability(registry);
    }
}
