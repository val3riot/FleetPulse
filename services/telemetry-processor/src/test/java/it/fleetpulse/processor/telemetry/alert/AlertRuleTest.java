package it.fleetpulse.processor.telemetry.alert;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertRuleTest {
    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final AlertVehicle VEHICLE = new AlertVehicle(VEHICLE_ID, 90_000);

    @Test
    void engineTemperatureFiresOnlyAboveThreshold() {
        var rule = new EngineTemperatureRule(110.0);

        assertThat(rule.evaluate(VEHICLE, sample(Math.nextDown(110.0), 12.6, 85_000)))
            .isEmpty();
        assertThat(rule.evaluate(VEHICLE, sample(110.0, 12.6, 85_000))).isEmpty();
        assertThat(rule.evaluate(VEHICLE, sample(Math.nextUp(110.0), 12.6, 85_000)))
            .contains(new AlertCandidate(VEHICLE_ID, MESSAGE_ID,
                AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH,
                EngineTemperatureRule.DESCRIPTION));
    }

    @Test
    void batteryVoltageFiresOnlyBelowThreshold() {
        var rule = new BatteryVoltageRule(11.8);

        assertThat(rule.evaluate(VEHICLE, sample(90.0, Math.nextDown(11.8), 85_000)))
            .contains(new AlertCandidate(VEHICLE_ID, MESSAGE_ID,
                AlertType.BATTERY_VOLTAGE_LOW, AlertSeverity.HIGH,
                BatteryVoltageRule.DESCRIPTION));
        assertThat(rule.evaluate(VEHICLE, sample(90.0, 11.8, 85_000))).isEmpty();
        assertThat(rule.evaluate(VEHICLE, sample(90.0, Math.nextUp(11.8), 85_000)))
            .isEmpty();
    }

    @Test
    void serviceDueFiresAtAndAboveVehicleThreshold() {
        var rule = new ServiceDueRule();

        assertThat(rule.evaluate(VEHICLE, sample(90.0, 12.6, 89_999))).isEmpty();
        assertThat(rule.evaluate(VEHICLE, sample(90.0, 12.6, 90_000)))
            .contains(new AlertCandidate(VEHICLE_ID, MESSAGE_ID, AlertType.SERVICE_DUE,
                AlertSeverity.MEDIUM, ServiceDueRule.DESCRIPTION));
        assertThat(rule.evaluate(VEHICLE, sample(90.0, 12.6, 90_001))).isPresent();
    }

    @Test
    void zeroServiceThresholdMeansDueFromFirstSample() {
        var vehicle = new AlertVehicle(VEHICLE_ID, 0);

        assertThat(new ServiceDueRule().evaluate(vehicle, sample(90.0, 12.6, 0))).isPresent();
    }

    @Test
    void rejectsSampleBelongingToAnotherVehicle() {
        var otherVehicleSample = new AlertTelemetrySample(MESSAGE_ID, UUID.randomUUID(),
            90.0, 12.6, 85_000);

        assertThatThrownBy(() -> new ServiceDueRule().evaluate(VEHICLE, otherVehicleSample))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("sample vehicleId must match vehicle id");
    }

    private static AlertTelemetrySample sample(double temperature, double batteryVoltage,
        long odometerKm) {
        return new AlertTelemetrySample(MESSAGE_ID, VEHICLE_ID, temperature, batteryVoltage,
            odometerKm);
    }
}
