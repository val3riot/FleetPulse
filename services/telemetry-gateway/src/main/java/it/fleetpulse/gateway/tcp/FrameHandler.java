package it.fleetpulse.gateway.tcp;

import it.fleetpulse.protocol.TelemetryMessage;

@FunctionalInterface
public interface FrameHandler {

    void handle(TelemetryMessage message);
}
