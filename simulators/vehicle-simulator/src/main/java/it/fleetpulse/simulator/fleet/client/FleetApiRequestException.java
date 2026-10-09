package it.fleetpulse.simulator.fleet.client;

public final class FleetApiRequestException extends FleetApiException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final int statusCode;

    public FleetApiRequestException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
