package it.fleetpulse.processor.telemetry.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class AlertThresholdPropertiesTest {
    private static final String PREFIX = "fleetpulse.telemetry.alerts.";
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsValidThresholdsAndCreatesEvaluator() {
        runner.withPropertyValues(
            PREFIX + "maximum-engine-temperature-c=110.0",
            PREFIX + "minimum-battery-voltage=11.8"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(AlertThresholdProperties.class);
            assertThat(properties.maximumEngineTemperatureC()).isEqualTo(110.0);
            assertThat(properties.minimumBatteryVoltage()).isEqualTo(11.8);
            assertThat(context).hasSingleBean(AlertEvaluator.class);
        });
    }

    @Test
    void rejectsMissingMaximumTemperature() {
        runner.withPropertyValues(PREFIX + "minimum-battery-voltage=11.8")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingMinimumVoltage() {
        runner.withPropertyValues(PREFIX + "maximum-engine-temperature-c=110.0")
            .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-273.16", "NaN", "Infinity", "-Infinity"})
    void rejectsInvalidMaximumTemperature(String value) {
        runner.withPropertyValues(
            PREFIX + "maximum-engine-temperature-c=" + value,
            PREFIX + "minimum-battery-voltage=11.8"
        ).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.1", "NaN", "Infinity", "-Infinity"})
    void rejectsInvalidMinimumVoltage(String value) {
        runner.withPropertyValues(
            PREFIX + "maximum-engine-temperature-c=110.0",
            PREFIX + "minimum-battery-voltage=" + value
        ).run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AlertThresholdProperties.class)
    @Import(AlertRuleConfiguration.class)
    static class PropertiesConfiguration {
    }
}
