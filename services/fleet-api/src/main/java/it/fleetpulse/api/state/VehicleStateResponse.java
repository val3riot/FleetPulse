package it.fleetpulse.api.state;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record VehicleStateResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID vehicleId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long lastSequenceNumber,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant lastSeenAt,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stale,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double speedKmh,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double engineTemperatureC,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double batteryVoltage,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long odometerKm,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double latitude,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) double longitude
) {
    static VehicleStateResponse from(LatestVehicleState state, boolean stale) {
        return new VehicleStateResponse(state.vehicleId(), state.lastSequenceNumber(),
            state.lastSeenAt(), stale, state.speedKmh(), state.engineTemperatureC(),
            state.batteryVoltage(), state.odometerKm(), state.latitude(), state.longitude());
    }
}
