package it.fleetpulse.api.state.redis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LatestStateProjectionPropertiesTest {
    private static final String PREFIX = "fleetpulse.api.state.cache.";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @ParameterizedTest
    @CsvSource({"1ms,1", "PT0.001S,1", "1,1", "5m,300000"})
    void bindsValidDurations(String ttl, long milliseconds) {
        runner.withPropertyValues(PREFIX + "ttl=" + ttl, PREFIX + "max-attempts=1")
            .run(context -> {
                assertThat(context).hasNotFailed();
                var properties = context.getBean(LatestStateProjectionProperties.class);
                assertThat(properties.ttl()).isEqualTo(Duration.ofMillis(milliseconds));
                assertThat(properties.maxAttempts()).isEqualTo(1);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1ms", "PT0.000999999S", "PT1ms"})
    void rejectsInvalidTtl(String ttl) {
        runner.withPropertyValues(PREFIX + "ttl=" + ttl, PREFIX + "max-attempts=1")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingTtl() {
        runner.withPropertyValues(PREFIX + "max-attempts=1")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveAttempts(int attempts) {
        runner.withPropertyValues(PREFIX + "ttl=5m", PREFIX + "max-attempts=" + attempts)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingAttempts() {
        runner.withPropertyValues(PREFIX + "ttl=5m")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LatestStateProjectionProperties.class)
    static class PropertiesConfiguration {
    }
}
