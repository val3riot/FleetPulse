package it.fleetpulse.api.state;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("fleetpulse.api.state")
public record VehicleStateProperties(@NotNull @DurationMin(millis = 1) Duration staleAfter) {
}
