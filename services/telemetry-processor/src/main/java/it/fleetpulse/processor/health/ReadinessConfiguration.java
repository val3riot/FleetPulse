package it.fleetpulse.processor.health;

import java.time.Duration;
import java.util.List;
import it.fleetpulse.processor.telemetry.kafka.KafkaTopicsProperties;
import it.fleetpulse.processor.telemetry.kafka.RawTelemetryEventListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

@Configuration(proxyBeanMethods = false)
public class ReadinessConfiguration {
    @Bean
    KafkaReadinessHealthIndicator kafkaHealthIndicator(KafkaAdmin admin, KafkaTopicsProperties topics,
            @Value("${fleetpulse.health.kafka-timeout:1s}") Duration timeout) {
        return new KafkaReadinessHealthIndicator(admin.getConfigurationProperties(),
            List.of(topics.raw(), topics.rejected(), topics.deadLetter()), timeout);
    }

    @Bean
    HealthIndicator consumerHealthIndicator(KafkaListenerEndpointRegistry registry) {
        return () -> {
            var container = registry.getListenerContainer(RawTelemetryEventListener.LISTENER_ID);
            // Zero assigned partitions and temporary pause/rebalance are valid states.
            return container != null && container.isRunning()
                ? Health.up().build() : Health.down().build();
        };
    }
}
