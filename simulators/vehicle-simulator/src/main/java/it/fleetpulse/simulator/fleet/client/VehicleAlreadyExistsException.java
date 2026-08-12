package it.fleetpulse.simulator.fleet.client;

public final class VehicleAlreadyExistsException extends FleetApiException {

    public VehicleAlreadyExistsException(String externalCode, Throwable cause) {
        super("Vehicle already exists: " + externalCode, cause);
    }
}
