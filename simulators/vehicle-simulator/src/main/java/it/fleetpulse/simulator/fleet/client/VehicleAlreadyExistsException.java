package it.fleetpulse.simulator.fleet.client;

public final class VehicleAlreadyExistsException extends FleetApiException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public VehicleAlreadyExistsException(String externalCode, Throwable cause) {
        super("Vehicle already exists: " + externalCode, cause);
    }
}
