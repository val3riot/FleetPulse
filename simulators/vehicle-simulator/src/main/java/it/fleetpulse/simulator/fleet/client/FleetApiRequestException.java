package it.fleetpulse.simulator.fleet.client;

public final class FleetApiRequestException extends FleetApiException {

    private final int statusCode;

    public FleetApiRequestException(int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
