package it.fleetpulse.simulator.fleet.client;

public record CreateFleetVehicleCommand(
        String externalCode,
        String plate,
        int serviceIntervalKm,
        long nextServiceAtKm
) {
    public CreateFleetVehicleCommand {
        if (externalCode == null || externalCode.isBlank()) {
            throw new IllegalArgumentException("externalCode must not be blank");
        }
        if (plate == null || plate.isBlank()) {
            throw new IllegalArgumentException("plate must not be blank");
        }
        if (serviceIntervalKm <= 0) {
            throw new IllegalArgumentException("serviceIntervalKm must be greater than zero");
        }
        if (nextServiceAtKm < 0) {
            throw new IllegalArgumentException("nextServiceAtKm must be non-negative");
        }
    }
}
