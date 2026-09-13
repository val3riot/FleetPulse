package it.fleetpulse.processor.telemetry.alert;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlertEvaluatorTest {
    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private final AlertEvaluator evaluator = new AlertEvaluator(List.of(
        new EngineTemperatureRule(110.0),
        new BatteryVoltageRule(11.8),
        new ServiceDueRule()
    ));

    @Test
    void returnsNoCandidatesWhenNoRuleMatches() {
        var sample = new AlertTelemetrySample(MESSAGE_ID, VEHICLE_ID, 90.0, 12.6, 89_999);

        assertThat(evaluator.evaluate(new AlertVehicle(VEHICLE_ID, 90_000), sample)).isEmpty();
    }

    @Test
    void returnsAllMatchingCandidatesInStableRuleOrder() {
        var vehicle = new AlertVehicle(VEHICLE_ID, 90_000);
        var sample = new AlertTelemetrySample(MESSAGE_ID, VEHICLE_ID, 111.0, 11.7, 90_000);

        var firstEvaluation = evaluator.evaluate(vehicle, sample);
        var secondEvaluation = evaluator.evaluate(vehicle, sample);

        assertThat(firstEvaluation).extracting(AlertCandidate::type).containsExactly(
            AlertType.ENGINE_TEMPERATURE_HIGH,
            AlertType.BATTERY_VOLTAGE_LOW,
            AlertType.SERVICE_DUE
        );
        assertThat(secondEvaluation).isEqualTo(firstEvaluation);
    }
}
