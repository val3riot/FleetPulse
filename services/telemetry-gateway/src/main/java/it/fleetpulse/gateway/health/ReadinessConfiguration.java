package it.fleetpulse.gateway.health;

import java.time.Duration;
import java.util.List;
import it.fleetpulse.gateway.tcp.TcpServerLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
public class ReadinessConfiguration {
    @Bean
    KafkaReadinessHealthIndicator kafkaHealthIndicator(KafkaAdmin admin,
            @Value("${fleetpulse.kafka.topics.raw}") String topic,
            @Value("${fleetpulse.health.kafka-timeout:1s}") Duration timeout) {
        return new KafkaReadinessHealthIndicator(admin.getConfigurationProperties(), List.of(topic),
                timeout);
    }

    @Bean
    HealthIndicator tcpHealthIndicator(ObjectProvider<TcpServerLifecycle> lifecycle) {
        return () -> {
            TcpServerLifecycle server = lifecycle.getIfAvailable();
            return server != null && server.isRunning()
                    ? Health.up().build()
                    : Health.down().build();
        };
    }
}
