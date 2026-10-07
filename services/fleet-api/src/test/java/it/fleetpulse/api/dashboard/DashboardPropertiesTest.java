package it.fleetpulse.api.dashboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(Config.class);

    @Test
    void bindsValidConfiguration() {
        runner.withPropertyValues("fleetpulse.api.dashboard.reporting-window=1m",
            "fleetpulse.api.dashboard.relevant-alerts-limit=10").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(DashboardProperties.class))
                    .isEqualTo(new DashboardProperties(Duration.ofMinutes(1), 10));
            });
    }

    @ParameterizedTest
    @CsvSource({"1ms,1", "1d,100"})
    void acceptsConfigurationAtBothLimits(String duration, int limit) {
        runner.withPropertyValues("fleetpulse.api.dashboard.reporting-window=" + duration,
            "fleetpulse.api.dashboard.relevant-alerts-limit=" + limit)
            .run(context -> assertThat(context).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0ms", "-1s", "PT0.000999999S", "2d", "invalid"})
    void invalidWindowPreventsStartup(String value) {
        runner.withPropertyValues("fleetpulse.api.dashboard.reporting-window=" + value,
            "fleetpulse.api.dashboard.relevant-alerts-limit=10")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "invalid"})
    void invalidLimitPreventsStartup(String value) {
        runner.withPropertyValues("fleetpulse.api.dashboard.reporting-window=1m",
            "fleetpulse.api.dashboard.relevant-alerts-limit=" + value)
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void missingWindowOrLimitPreventsStartup() {
        runner.withPropertyValues("fleetpulse.api.dashboard.relevant-alerts-limit=10")
            .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("fleetpulse.api.dashboard.reporting-window=1m")
            .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DashboardProperties.class)
    static class Config { }
}
