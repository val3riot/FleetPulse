package it.fleetpulse.processor.telemetry.vehicle;

import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleEligibilityEvaluatorTest {

    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    @Test
    void acceptsActiveVehicle() {
        VehicleRegistry registry = id -> Optional.of(VehicleStatus.ACTIVE);
        VehicleEligibilityEvaluator evaluator = new VehicleEligibilityEvaluator(registry);

        assertThat(evaluator.rejectionReason(VEHICLE_ID)).isEmpty();
    }

    @Test
    void rejectsUnknownVehicle() {
        VehicleRegistry registry = id -> Optional.empty();
        VehicleEligibilityEvaluator evaluator = new VehicleEligibilityEvaluator(registry);

        assertThat(evaluator.rejectionReason(VEHICLE_ID)).contains(
            TelemetryRejectionReason.UNKNOWN_VEHICLE);
    }

    @Test
    void rejectsDisabledVehicle() {
        VehicleRegistry registry = id -> Optional.of(VehicleStatus.DISABLED);
        VehicleEligibilityEvaluator evaluator = new VehicleEligibilityEvaluator(registry);

        assertThat(evaluator.rejectionReason(VEHICLE_ID)).contains(
            TelemetryRejectionReason.VEHICLE_DISABLED);
    }
}
