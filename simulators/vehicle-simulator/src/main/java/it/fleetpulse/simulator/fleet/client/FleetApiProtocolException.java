package it.fleetpulse.simulator.fleet.client;

public final class FleetApiProtocolException extends FleetApiException {

    public FleetApiProtocolException(String message) {
        super(message);
    }

    public FleetApiProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
