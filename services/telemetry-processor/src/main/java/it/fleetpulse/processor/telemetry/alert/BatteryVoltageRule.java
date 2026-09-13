package it.fleetpulse.processor.telemetry.alert;

import java.util.Optional;

public final class BatteryVoltageRule implements AlertRule {
    public static final String DESCRIPTION = "Tensione batteria sotto soglia";

    private final double minimumVoltage;

    public BatteryVoltageRule(double minimumVoltage) {
        if (!Double.isFinite(minimumVoltage) || minimumVoltage <= 0) {
            throw new IllegalArgumentException("minimumVoltage must be finite and positive");
        }
        this.minimumVoltage = minimumVoltage;
    }

    @Override
    public Optional<AlertCandidate> evaluate(AlertVehicle vehicle, AlertTelemetrySample sample) {
        AlertRule.requireMatchingVehicle(vehicle, sample);
        if (sample.batteryVoltage() >= minimumVoltage) {
            return Optional.empty();
        }
        return Optional.of(new AlertCandidate(vehicle.id(), sample.messageId(),
            AlertType.BATTERY_VOLTAGE_LOW, AlertSeverity.HIGH, DESCRIPTION));
    }
}
