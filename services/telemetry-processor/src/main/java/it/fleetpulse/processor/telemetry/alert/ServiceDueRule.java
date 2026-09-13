package it.fleetpulse.processor.telemetry.alert;

import java.util.Optional;

public final class ServiceDueRule implements AlertRule {
    public static final String DESCRIPTION = "Manutenzione programmata raggiunta";

    @Override
    public Optional<AlertCandidate> evaluate(AlertVehicle vehicle, AlertTelemetrySample sample) {
        AlertRule.requireMatchingVehicle(vehicle, sample);
        if (sample.odometerKm() < vehicle.nextServiceAtKm()) {
            return Optional.empty();
        }
        return Optional.of(new AlertCandidate(vehicle.id(), sample.messageId(),
            AlertType.SERVICE_DUE, AlertSeverity.MEDIUM, DESCRIPTION));
    }
}
