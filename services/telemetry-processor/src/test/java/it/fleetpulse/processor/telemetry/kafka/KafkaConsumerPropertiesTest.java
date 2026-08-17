package it.fleetpulse.processor.telemetry.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerPropertiesTest {
    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner().withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsValidRetryConfiguration() {
        contextRunner.withPropertyValues("fleetpulse.kafka.consumer.group-id=test-group",
            "fleetpulse.kafka.consumer.retry-max-attempts=3",
            "fleetpulse.kafka.consumer.retry-initial-backoff=500ms",
            "fleetpulse.kafka.consumer.retry-max-backoff=5s",
            "fleetpulse.kafka.consumer.retry-multiplier=2.0",
            "fleetpulse.kafka.consumer.retry-jitter-ratio=0.2").run(context -> {
            assertThat(context).hasNotFailed();

            KafkaConsumerProperties properties = context.getBean(KafkaConsumerProperties.class);

            assertThat(properties.groupId()).isEqualTo("test-group");
            assertThat(properties.retryMaxAttempts()).isEqualTo(3);
            assertThat(properties.retryInitialBackoff()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.retryMaxBackoff()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.retryMultiplier()).isEqualTo(2.0);
            assertThat(properties.retryJitterRatio()).isEqualTo(0.2);
        });
    }

    @Test
    void rejectsInitialBackoffGreaterThanMaximum() {
        contextRunner.withPropertyValues("fleetpulse.kafka.consumer.group-id=test-group",
                "fleetpulse.kafka.consumer.retry-max-attempts=3",
                "fleetpulse.kafka.consumer.retry-initial-backoff=10s",
                "fleetpulse.kafka.consumer.retry-max-backoff=5s",
                "fleetpulse.kafka.consumer.retry-multiplier=2.0",
                "fleetpulse.kafka.consumer.retry-jitter-ratio=0.2")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsInvalidRetryLimits() {
        contextRunner.withPropertyValues("fleetpulse.kafka.consumer.group-id=test-group",
                "fleetpulse.kafka.consumer.retry-max-attempts=-1",
                "fleetpulse.kafka.consumer.retry-initial-backoff=500ms",
                "fleetpulse.kafka.consumer.retry-max-backoff=5s",
                "fleetpulse.kafka.consumer.retry-multiplier=0.5",
                "fleetpulse.kafka.consumer.retry-jitter-ratio=1.1")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaConsumerProperties.class)
    static class PropertiesConfiguration {
    }
}
