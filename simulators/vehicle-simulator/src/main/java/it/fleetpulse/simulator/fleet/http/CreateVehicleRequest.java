package it.fleetpulse.simulator.fleet.http;

public record CreateVehicleRequest(
        String externalCode,
        String plate,
        int serviceIntervalKm,
        long nextServiceAtKm
) {
}
