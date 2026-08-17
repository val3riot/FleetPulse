package it.fleetpulse.contracts.telemetry;

public record TelemetryData(
        double speedKmh,
        double engineTemperatureC,
        double batteryVoltage,
        long odometerKm,
        double latitude,
        double longitude
) {
}
