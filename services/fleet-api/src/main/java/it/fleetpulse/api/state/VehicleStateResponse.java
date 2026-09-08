package it.fleetpulse.api.state;

import java.time.Instant;
import java.util.UUID;

public record VehicleStateResponse(
    UUID vehicleId,
    long lastSequenceNumber,
    Instant lastSeenAt,
    boolean stale,
    double speedKmh,
    double engineTemperatureC,
    double batteryVoltage,
    long odometerKm,
    double latitude,
    double longitude
) {
    static VehicleStateResponse from(LatestVehicleState state, boolean stale) {
        return new VehicleStateResponse(state.vehicleId(), state.lastSequenceNumber(),
            state.lastSeenAt(), stale, state.speedKmh(), state.engineTemperatureC(),
            state.batteryVoltage(), state.odometerKm(), state.latitude(), state.longitude());
    }
}
