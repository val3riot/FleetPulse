package it.fleetpulse.api.state;

public class LatestStateProjectionException extends RuntimeException {
    public LatestStateProjectionException(String message) {
        super(message);
    }

    public LatestStateProjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
