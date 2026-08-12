package it.fleetpulse.simulator.fleet.client;

public class FleetApiException extends RuntimeException {

    public FleetApiException(String message) {
        super(message);
    }

    public FleetApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
