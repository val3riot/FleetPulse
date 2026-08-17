package it.fleetpulse.processor.telemetry.vehicle;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleRejectionObservabilityTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final VehicleRejectionObservability observability =
        new VehicleRejectionObservability(registry);

    @Test
    void recordsRejectionsWithBoundedReasonTag() {
        observability.rejected(event(TelemetryRejectionReason.UNKNOWN_VEHICLE));
        observability.rejected(event(TelemetryRejectionReason.UNKNOWN_VEHICLE));
        observability.rejected(event(TelemetryRejectionReason.VEHICLE_DISABLED));

        assertThat(rejections(TelemetryRejectionReason.UNKNOWN_VEHICLE)).isEqualTo(2.0);
        assertThat(rejections(TelemetryRejectionReason.VEHICLE_DISABLED)).isEqualTo(1.0);
    }

    @Test
    void registersBothReasonsBeforeFirstRejection() {
        assertThat(rejections(TelemetryRejectionReason.UNKNOWN_VEHICLE)).isZero();
        assertThat(rejections(TelemetryRejectionReason.VEHICLE_DISABLED)).isZero();
    }

    private double rejections(TelemetryRejectionReason reason) {
        return registry.get("fleetpulse.processor.rejections").tag("reason", reason.name())
            .counter().count();
    }

    private static TelemetryRejectedEvent event(TelemetryRejectionReason reason) {
        return new TelemetryRejectedEvent(UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), reason,
            Instant.parse("2026-08-17T10:00:00Z"), "telemetry.raw.v1", 1, 42L);
    }
}
