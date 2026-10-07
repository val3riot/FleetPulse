package it.fleetpulse.api.vehicle;

/** Projection del conteggio dei veicoli per stato, senza materializzare entity. */
public record VehicleStatusCount(VehicleStatus status, long total) {
}
