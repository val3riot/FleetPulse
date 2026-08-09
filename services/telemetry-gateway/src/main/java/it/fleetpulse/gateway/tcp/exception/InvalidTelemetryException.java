package it.fleetpulse.gateway.tcp.exception;

import java.io.IOException;

public final class InvalidTelemetryException extends IOException {

    public InvalidTelemetryException(String message) {
        super(message);
    }
}
