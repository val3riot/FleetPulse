package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryDeadLetterEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;

public interface TelemetryTerminalEventPublisher {

    void publishRejected(TelemetryRejectedEvent event);

    void publishDeadLetter(TelemetryDeadLetterEvent event);
}