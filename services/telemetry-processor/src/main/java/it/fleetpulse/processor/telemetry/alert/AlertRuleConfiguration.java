package it.fleetpulse.processor.telemetry.alert;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class AlertRuleConfiguration {

    @Bean
    AlertEvaluator alertEvaluator(AlertThresholdProperties properties) {
        return new AlertEvaluator(List.of(
            new EngineTemperatureRule(properties.maximumEngineTemperatureC()),
            new BatteryVoltageRule(properties.minimumBatteryVoltage()),
            new ServiceDueRule()
        ));
    }
}
