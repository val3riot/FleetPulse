package it.fleetpulse.processor.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;

@FunctionalInterface
public interface TelemetryEventHandler {
    void handle(TelemetryEvent event, TelemetrySource source);
}
