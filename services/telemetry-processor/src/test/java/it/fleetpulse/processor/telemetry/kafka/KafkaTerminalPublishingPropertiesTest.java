package it.fleetpulse.processor.telemetry.kafka;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTerminalPublishingPropertiesTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            PropertiesConfiguration.class
                    );

    @Test
    void bindsPositiveConfirmationTimeout() {
        contextRunner
                .withPropertyValues(
                        "fleetpulse.kafka.terminal-publication"
                                + ".confirmation-timeout=5s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            KafkaTerminalPublishingProperties.class
                    ).confirmationTimeout()).isEqualTo(
                            Duration.ofSeconds(5)
                    );
                });
    }

    @Test
    void rejectsNonPositiveConfirmationTimeout() {
        contextRunner
                .withPropertyValues(
                        "fleetpulse.kafka.terminal-publication"
                                + ".confirmation-timeout=0s"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
            KafkaTerminalPublishingProperties.class
    )
    static class PropertiesConfiguration {
    }
}
