package it.fleetpulse.processor.telemetry.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "fleetpulse.telemetry.alerts")
@Validated
public record AlertThresholdProperties(
        @NotNull @DecimalMin("-273.15") Double maximumEngineTemperatureC,
        @NotNull @Positive Double minimumBatteryVoltage
) {

    public AlertThresholdProperties {
        requireFinite(maximumEngineTemperatureC, "maximumEngineTemperatureC");
        requireFinite(minimumBatteryVoltage, "minimumBatteryVoltage");
    }

    private static void requireFinite(Double value, String field) {
        if (value != null && !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }
}
