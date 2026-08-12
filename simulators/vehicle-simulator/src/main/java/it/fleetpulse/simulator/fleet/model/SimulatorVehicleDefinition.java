package it.fleetpulse.simulator.fleet.model;

import it.fleetpulse.simulator.config.VehicleProperties;

import java.util.Objects;

public record SimulatorVehicleDefinition(
        int index,
        String externalCode,
        String plate,
        int serviceIntervalKm,
        long nextServiceAtKm
) {

    public static SimulatorVehicleDefinition of(int index, VehicleProperties properties) {
        if (index <= 0) {
            throw new IllegalArgumentException("index must be greater than zero");
        }
        Objects.requireNonNull(properties, "properties must not be null");

        double nextService = Math.ceil(properties.initialOdometerKm())
                + properties.serviceIntervalKm();
        if (nextService > Long.MAX_VALUE) {
            throw new IllegalArgumentException("nextServiceAtKm exceeds the supported range");
        }

        return new SimulatorVehicleDefinition(
                index,
                "FP-SIM-%03d".formatted(index),
                "SIM%03d".formatted(index),
                properties.serviceIntervalKm(),
                (long) nextService
        );
    }
}
