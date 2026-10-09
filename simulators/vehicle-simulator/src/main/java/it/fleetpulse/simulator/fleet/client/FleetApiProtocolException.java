package it.fleetpulse.simulator.fleet.client;

public final class FleetApiProtocolException extends FleetApiException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public FleetApiProtocolException(String message) {
        super(message);
    }

    public FleetApiProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
