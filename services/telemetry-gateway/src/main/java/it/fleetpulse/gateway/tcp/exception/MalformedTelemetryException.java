package it.fleetpulse.gateway.tcp.exception;

import java.io.IOException;

public final class MalformedTelemetryException extends IOException {

    public MalformedTelemetryException(String message) {
        super(message);
    }

    public MalformedTelemetryException(Throwable cause) {
        super("Telemetry payload is malformed or cannot be decoded", cause);
    }
}
