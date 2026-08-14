package it.fleetpulse.gateway.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface TelemetryPublisher {

    CompletionStage<Void> publish (TelemetryEvent event);
}
