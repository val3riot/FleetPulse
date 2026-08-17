package it.fleetpulse.processor.telemetry.kafka;

public final class TelemetryTerminalPublicationException extends RuntimeException{

    public TelemetryTerminalPublicationException(String message, Throwable cause){
        super(message, cause);
    }
}
