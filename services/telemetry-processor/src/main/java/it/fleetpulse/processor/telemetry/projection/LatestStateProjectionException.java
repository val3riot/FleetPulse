package it.fleetpulse.processor.telemetry.projection;

public class LatestStateProjectionException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;
    public LatestStateProjectionException(String message) {
        super(message);
    }

    public LatestStateProjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
