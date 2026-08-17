package it.fleetpulse.processor.telemetry.vehicle;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import it.fleetpulse.processor.telemetry.TelemetrySource;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

@Component
public final class TelemetryRejectedEventFactory {

    private final Clock clock;

    public TelemetryRejectedEventFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public TelemetryRejectedEvent create(
            TelemetryEvent event,
            TelemetryRejectionReason reason,
            TelemetrySource source
    ) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(source, "source must not be null");

        return new TelemetryRejectedEvent(
                event.messageId(),
                event.vehicleId(),
                reason,
                clock.instant(),
                source.topic(),
                source.partition(),
                source.offset()
        );
    }
}
