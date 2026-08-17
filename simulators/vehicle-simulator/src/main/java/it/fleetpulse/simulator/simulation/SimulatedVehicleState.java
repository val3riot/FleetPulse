package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;

import java.util.Objects;
import java.util.UUID;

public record SimulatedVehicleState(
        UUID vehicleId,
        String externalCode,
        long sequenceNumber,
        double speedKmh,
        double engineTemperatureC,
        double batteryVoltage,
        double odometerKm,
        double latitude,
        double longitude
) {
    private static final double INITIAL_SPEED_KMH = 0.0;
    private static final double INITIAL_ENGINE_TEMPERATURE_C = 85.0;
    private static final double INITIAL_BATTERY_VOLTAGE = 13.8;

    public SimulatedVehicleState {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");
        if (externalCode == null || externalCode.isBlank()) {
            throw new IllegalArgumentException("externalCode must not be blank");
        }
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be non-negative");
        }
        requireFiniteNonNegative(speedKmh, "speedKmh");
        requireFinite(engineTemperatureC, "engineTemperatureC");
        requireFiniteNonNegative(batteryVoltage, "batteryVoltage");
        requireFiniteNonNegative(odometerKm, "odometerKm");
        requireRange(latitude, -90.0, 90.0, "latitude");
        requireRange(longitude, -180.0, 180.0, "longitude");
    }

    public static SimulatedVehicleState initial(ProvisionedVehicle vehicle,
        double initialOdometerKm, double latitude, double longitude) {
        Objects.requireNonNull(vehicle, "vehicle must not be null");
        return new SimulatedVehicleState(vehicle.vehicleId(), vehicle.externalCode(), 0,
            INITIAL_SPEED_KMH, INITIAL_ENGINE_TEMPERATURE_C, INITIAL_BATTERY_VOLTAGE,
            initialOdometerKm, latitude, longitude);
    }

    public SimulatedVehicleState next(double speedKmh, double engineTemperatureC,
        double batteryVoltage, double odometerKm, double latitude, double longitude) {
        if (sequenceNumber == Long.MAX_VALUE) {
            throw new IllegalStateException("sequenceNumber exhausted");
        }
        if (odometerKm < this.odometerKm) {
            throw new IllegalArgumentException("odometerKm must not decrease");
        }
        return new SimulatedVehicleState(vehicleId, externalCode, sequenceNumber + 1, speedKmh,
            engineTemperatureC, batteryVoltage, odometerKm, latitude, longitude);
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static void requireFiniteNonNegative(double value, String field) {
        requireFinite(value, field);
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }

    private static void requireRange(double value, double minimum, double maximum, String field) {
        requireFinite(value, field);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                field + " must be between " + minimum + " and " + maximum);
        }
    }
}
