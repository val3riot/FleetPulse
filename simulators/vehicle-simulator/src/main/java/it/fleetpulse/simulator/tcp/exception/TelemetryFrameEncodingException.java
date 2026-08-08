package it.fleetpulse.simulator.tcp.exception;

import java.io.IOException;

public final class TelemetryFrameEncodingException extends IOException {

    public TelemetryFrameEncodingException(String message) {
        super(message);
    }
}
