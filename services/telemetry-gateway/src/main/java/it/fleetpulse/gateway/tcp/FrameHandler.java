package it.fleetpulse.gateway.tcp;

import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.TelemetryMessage;

@FunctionalInterface
public interface FrameHandler {

    TelemetryAck handle(TelemetryMessage message);
}
