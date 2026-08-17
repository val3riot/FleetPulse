package it.fleetpulse.api.vehicle;

public record VehicleSearchCriteria(
    String query,
    VehicleStatus status
) {
}
