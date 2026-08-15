package it.fleetpulse.gateway.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.protocol.TelemetryMessage;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class TelemetryEventMapper {
    private final Clock clock;

    public TelemetryEventMapper(Clock clock){
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public TelemetryEvent map(TelemetryMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        return new TelemetryEvent(
                TelemetryEventVersions.V1,
                message.messageId(),
                message.vehicleId(),
                message.sequenceNumber(),
                message.observedAt(),
                Instant.now(clock),
                new TelemetryData(
                        message.speedKmh(),
                        message.engineTemperatureC(),
                        message.batteryVoltage(),
                        message.odometerKm(),
                        message.latitude(),
                        message.longitude()
                )
        );
    }
}
