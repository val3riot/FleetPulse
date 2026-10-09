package it.fleetpulse.simulator.fleet.client;

public final class FleetApiUnavailableException extends FleetApiException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public FleetApiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
