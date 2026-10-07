package it.fleetpulse.api.dashboard;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMax;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("fleetpulse.api.dashboard")
public record DashboardProperties(
    @NotNull @DurationMin(millis = 1) @DurationMax(days = 1) Duration reportingWindow,
    @Min(1) @Max(100) int relevantAlertsLimit
) {
}
