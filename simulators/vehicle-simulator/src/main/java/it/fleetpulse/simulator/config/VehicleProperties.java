package it.fleetpulse.simulator.config;

public record VehicleProperties(
    int serviceIntervalKm,
    double initialOdometerKm
) {

    public VehicleProperties {
        if (serviceIntervalKm <= 0) {
            throw new IllegalArgumentException(
                "vehicle.serviceIntervalKm must be greater than zero");
        }
        if (!Double.isFinite(initialOdometerKm) || initialOdometerKm < 0) {
            throw new IllegalArgumentException(
                "vehicle.initialOdometerKm must be finite and non-negative");
        }
    }
}
