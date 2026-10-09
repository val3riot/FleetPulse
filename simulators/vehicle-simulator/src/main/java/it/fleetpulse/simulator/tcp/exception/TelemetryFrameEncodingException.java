package it.fleetpulse.simulator.tcp.exception;

import java.io.IOException;

public final class TelemetryFrameEncodingException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public TelemetryFrameEncodingException(String message) {
        super(message);
    }
}
