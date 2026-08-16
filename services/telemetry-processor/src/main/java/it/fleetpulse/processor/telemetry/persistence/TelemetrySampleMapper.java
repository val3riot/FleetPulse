package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public final class TelemetrySampleMapper {
    public TelemetrySampleEntity toEntity(
            TelemetryEvent event,
            Instant processedAt
    ) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(
                processedAt,
                "processedAt must not be null"
        );

        TelemetryData telemetry = Objects.requireNonNull(
                event.telemetry(),
                "event telemetry must not be null"
        );

        return new TelemetrySampleEntity(
                event.messageId(),
                event.vehicleId(),
                event.sequenceNumber(),
                event.observedAt(),
                event.receivedAt(),
                processedAt,
                telemetry.speedKmh(),
                telemetry.engineTemperatureC(),
                telemetry.batteryVoltage(),
                telemetry.odometerKm(),
                telemetry.latitude(),
                telemetry.longitude()
        );
    }
}
