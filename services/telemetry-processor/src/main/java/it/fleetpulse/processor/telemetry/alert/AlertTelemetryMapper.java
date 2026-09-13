package it.fleetpulse.processor.telemetry.alert;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class AlertTelemetryMapper {

    public AlertTelemetrySample toSample(TelemetryEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        TelemetryData telemetry =
            Objects.requireNonNull(event.telemetry(), "event telemetry must not be null");

        return new AlertTelemetrySample(event.messageId(), event.vehicleId(),
            telemetry.engineTemperatureC(), telemetry.batteryVoltage(), telemetry.odometerKm());
    }
}
