package it.fleetpulse.gateway.tcp.exception;

import java.io.IOException;

public final class InvalidTelemetryException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public InvalidTelemetryException(String message) {
        super(message);
    }
}
