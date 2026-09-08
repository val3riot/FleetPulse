package it.fleetpulse.api.state;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleStatePropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class);

    @Test
    void bindsDuration() {
        runner.withPropertyValues("fleetpulse.api.state.stale-after=1m").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(VehicleStateProperties.class).staleAfter())
                .isEqualTo(Duration.ofMinutes(1));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "PT0.000999999S", "invalid"})
    void invalidThresholdPreventsStartup(String value) {
        runner.withPropertyValues("fleetpulse.api.state.stale-after=" + value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingThresholdPreventsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(VehicleStateProperties.class)
    static class Config { }
}
