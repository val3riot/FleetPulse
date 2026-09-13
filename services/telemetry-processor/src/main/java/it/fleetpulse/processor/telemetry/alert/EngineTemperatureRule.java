package it.fleetpulse.processor.telemetry.alert;

import java.util.Optional;

public final class EngineTemperatureRule implements AlertRule {
    public static final String DESCRIPTION = "Temperatura motore oltre soglia";

    private final double maximumTemperatureC;

    public EngineTemperatureRule(double maximumTemperatureC) {
        if (!Double.isFinite(maximumTemperatureC) || maximumTemperatureC < -273.15) {
            throw new IllegalArgumentException(
                "maximumTemperatureC must be finite and not below absolute zero");
        }
        this.maximumTemperatureC = maximumTemperatureC;
    }

    @Override
    public Optional<AlertCandidate> evaluate(AlertVehicle vehicle, AlertTelemetrySample sample) {
        AlertRule.requireMatchingVehicle(vehicle, sample);
        if (sample.engineTemperatureC() <= maximumTemperatureC) {
            return Optional.empty();
        }
        return Optional.of(new AlertCandidate(vehicle.id(), sample.messageId(),
            AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH, DESCRIPTION));
    }

}
