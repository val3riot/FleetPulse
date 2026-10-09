package it.fleetpulse.simulator.fleet.client;

public class FleetApiException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public FleetApiException(String message) {
        super(message);
    }

    public FleetApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
