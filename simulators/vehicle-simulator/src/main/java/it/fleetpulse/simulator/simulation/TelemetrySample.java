package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.TelemetryMessage;

import java.util.Objects;

public record TelemetrySample(
    SimulatedVehicleState nextState,
    TelemetryMessage message
) {
    public TelemetrySample {
        Objects.requireNonNull(nextState, "nextState must not be null");
        Objects.requireNonNull(message, "message must not be null");
        if (!nextState.vehicleId().equals(message.vehicleId())) {
            throw new IllegalArgumentException("state and message vehicleId must match");
        }
        if (nextState.sequenceNumber() != message.sequenceNumber() + 1) {
            throw new IllegalArgumentException(
                "next state sequenceNumber must immediately follow message sequenceNumber");
        }
    }
}
