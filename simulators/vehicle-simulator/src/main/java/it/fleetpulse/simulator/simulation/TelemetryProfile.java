package it.fleetpulse.simulator.simulation;

@FunctionalInterface
public interface TelemetryProfile {

    TelemetrySample next(SimulatedVehicleState currentState);
}
