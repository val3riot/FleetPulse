package it.fleetpulse.processor.telemetry.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class KafkaTerminalPublishingConfiguration {

    @Bean
    TelemetryTerminalEventPublisher terminalEventPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            KafkaTopicsProperties topics,
            KafkaTerminalPublishingProperties properties
    ) {
        return new KafkaTelemetryTerminalEventPublisher(
                kafkaTemplate,
                topics,
                properties
        );
    }

    @Bean
    KafkaDeliveryAttemptResolver deliveryAttemptResolver() {
        return new KafkaDeliveryAttemptResolver();
    }

    @Bean
    KafkaOriginalPayloadResolver originalPayloadResolver(
            ObjectMapper objectMapper
    ) {
        return new KafkaOriginalPayloadResolver(objectMapper);
    }

    @Bean
    TelemetryDeadLetterEventFactory deadLetterEventFactory(
            Clock clock,
            KafkaOriginalPayloadResolver payloadResolver,
            KafkaDeliveryAttemptResolver attemptResolver
    ) {
        return new TelemetryDeadLetterEventFactory(
                clock,
                payloadResolver,
                attemptResolver
        );
    }

    @Bean
    ConsumerRecordRecoverer deadLetterRecoverer(
            TelemetryDeadLetterEventFactory eventFactory,
            TelemetryTerminalEventPublisher publisher
    ) {
        return new TelemetryDeadLetterRecoverer(
                eventFactory,
                publisher
        );
    }
}
