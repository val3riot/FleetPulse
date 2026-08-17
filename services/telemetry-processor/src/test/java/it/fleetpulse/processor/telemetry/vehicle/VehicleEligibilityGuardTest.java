package it.fleetpulse.processor.telemetry.vehicle;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import it.fleetpulse.processor.telemetry.TelemetrySource;
import it.fleetpulse.processor.telemetry.kafka.TelemetryTerminalEventPublisher;
import it.fleetpulse.processor.telemetry.kafka.TelemetryTerminalPublicationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class VehicleEligibilityGuardTest {

    private static final Instant REJECTED_AT = Instant.parse("2026-08-17T10:00:00Z");
    private static final TelemetrySource SOURCE = new TelemetrySource("telemetry.raw.v1", 1, 42L);

    @Test
    void allowsActiveVehicleWithoutPublishingRejection() {
        VehicleRegistry registry = id -> Optional.of(VehicleStatus.ACTIVE);
        TelemetryTerminalEventPublisher publisher = mock(TelemetryTerminalEventPublisher.class);
        VehicleEligibilityGuard guard = guard(registry, publisher);

        boolean rejected = guard.rejectIfIneligible(event(), SOURCE);

        assertThat(rejected).isFalse();
        verifyNoInteractions(publisher);
    }

    @Test
    void publishesUnknownVehicleRejection() {
        VehicleRegistry registry = id -> Optional.empty();

        assertPublishesRejection(registry, TelemetryRejectionReason.UNKNOWN_VEHICLE);
    }

    @Test
    void publishesDisabledVehicleRejection() {
        VehicleRegistry registry = id -> Optional.of(VehicleStatus.DISABLED);

        assertPublishesRejection(registry, TelemetryRejectionReason.VEHICLE_DISABLED);
    }

    @Test
    void doesNotRecordRejectionWhenPublicationFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryTerminalEventPublisher publisher = mock(TelemetryTerminalEventPublisher.class);
        TelemetryTerminalPublicationException failure =
            new TelemetryTerminalPublicationException("broker unavailable", new RuntimeException());
        doThrow(failure).when(publisher).publishRejected(any(TelemetryRejectedEvent.class));

        VehicleEligibilityEvaluator evaluator =
            new VehicleEligibilityEvaluator(id -> Optional.empty());
        TelemetryRejectedEventFactory factory =
            new TelemetryRejectedEventFactory(Clock.fixed(REJECTED_AT, ZoneOffset.UTC));
        VehicleRejectionObservability observability = new VehicleRejectionObservability(registry);
        VehicleEligibilityGuard guard =
            new VehicleEligibilityGuard(evaluator, factory, publisher, observability);

        assertThatThrownBy(() -> guard.rejectIfIneligible(event(), SOURCE)).isSameAs(failure);
        assertThat(registry.get("fleetpulse.processor.rejections")
            .tag("reason", TelemetryRejectionReason.UNKNOWN_VEHICLE.name()).counter()
            .count()).isZero();
    }

    private static void assertPublishesRejection(VehicleRegistry registry,
        TelemetryRejectionReason expectedReason) {
        TelemetryTerminalEventPublisher publisher = mock(TelemetryTerminalEventPublisher.class);
        VehicleEligibilityGuard guard = guard(registry, publisher);
        TelemetryEvent event = event();

        boolean rejected = guard.rejectIfIneligible(event, SOURCE);

        assertThat(rejected).isTrue();
        ArgumentCaptor<TelemetryRejectedEvent> captor = forClass(TelemetryRejectedEvent.class);
        verify(publisher).publishRejected(captor.capture());

        TelemetryRejectedEvent rejection = captor.getValue();
        assertThat(rejection.messageId()).isEqualTo(event.messageId());
        assertThat(rejection.vehicleId()).isEqualTo(event.vehicleId());
        assertThat(rejection.reason()).isEqualTo(expectedReason);
        assertThat(rejection.rejectedAt()).isEqualTo(REJECTED_AT);
        assertThat(rejection.sourceTopic()).isEqualTo(SOURCE.topic());
        assertThat(rejection.sourcePartition()).isEqualTo(SOURCE.partition());
        assertThat(rejection.sourceOffset()).isEqualTo(SOURCE.offset());
    }

    private static VehicleEligibilityGuard guard(VehicleRegistry registry,
        TelemetryTerminalEventPublisher publisher) {
        VehicleEligibilityEvaluator evaluator = new VehicleEligibilityEvaluator(registry);
        TelemetryRejectedEventFactory factory =
            new TelemetryRejectedEventFactory(Clock.fixed(REJECTED_AT, ZoneOffset.UTC));

        VehicleRejectionObservability observability =
            new VehicleRejectionObservability(new SimpleMeterRegistry());

        return new VehicleEligibilityGuard(evaluator, factory, publisher, observability);
    }

    private static TelemetryEvent event() {
        return new TelemetryEvent(TelemetryEventVersions.V1,
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), 42,
            Instant.parse("2026-08-01T10:15:30Z"), Instant.parse("2026-08-01T10:15:30.083Z"),
            new TelemetryData(72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964));
    }
}
