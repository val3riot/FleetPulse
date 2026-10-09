package it.fleetpulse.processor.telemetry.kafka;

public final class TelemetryTerminalPublicationException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public TelemetryTerminalPublicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
