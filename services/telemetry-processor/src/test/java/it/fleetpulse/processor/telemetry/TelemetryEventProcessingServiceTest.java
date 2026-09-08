package it.fleetpulse.processor.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.persistence.TelemetryPersistenceFailureClassifier;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleEntity;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleMapper;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleWriter;
import it.fleetpulse.processor.telemetry.vehicle.VehicleEligibilityGuard;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjection;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionObservability;
import it.fleetpulse.processor.telemetry.projection.LatestVehicleState;
import it.fleetpulse.processor.telemetry.projection.ProjectionUpdateResult;
import org.springframework.transaction.TransactionSystemException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

class TelemetryEventProcessingServiceTest {
    private static final Instant PROCESSED_AT = Instant.parse("2026-08-01T10:15:30.150Z");
    private static final TelemetrySource SOURCE = new TelemetrySource("telemetry.raw.v1", 1, 42L);

    private final TelemetrySampleWriter writer = mock(TelemetrySampleWriter.class);

    private final TelemetryPersistenceFailureClassifier failureClassifier =
        mock(TelemetryPersistenceFailureClassifier.class);
    private final VehicleEligibilityGuard eligibilityGuard = mock(VehicleEligibilityGuard.class);
    private final LatestStateProjection projection = mock(LatestStateProjection.class);
    private final LatestStateProjectionObservability observability = mock(LatestStateProjectionObservability.class);

    private final TelemetryEventProcessingService service =
        new TelemetryEventProcessingService(writer, new TelemetrySampleMapper(),
            Clock.fixed(PROCESSED_AT, ZoneOffset.UTC), failureClassifier, eligibilityGuard,
            projection, observability);

    @BeforeEach
    void returnEntityBeingSaved() {
        when(writer.insert(any(TelemetrySampleEntity.class))).thenAnswer(
            invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsVersionOne() {
        TelemetryEvent event = event(TelemetryEventVersions.V1);

        service.handle(event, SOURCE);

        ArgumentCaptor<TelemetrySampleEntity> captor =
            ArgumentCaptor.forClass(TelemetrySampleEntity.class);

        verify(writer).insert(captor.capture());

        TelemetrySampleEntity saved = captor.getValue();

        assertEquals(event.messageId(), saved.getMessageId());
        assertEquals(event.vehicleId(), saved.getVehicleId());
        assertEquals(PROCESSED_AT, saved.getProcessedAt());
    }

    @Test
    void projectsCompleteSampleAfterWriterReturnsAndRecordsOutcome() {
        var event = event(TelemetryEventVersions.V1);
        var expected = new LatestVehicleState(event.vehicleId(), event.sequenceNumber(), event.observedAt(),
            72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
        when(projection.updateIfNewer(expected)).thenReturn(ProjectionUpdateResult.UPDATED);

        service.handle(event, SOURCE);

        var order = inOrder(writer, projection, observability);
        order.verify(writer).insert(any(TelemetrySampleEntity.class));
        order.verify(projection).updateIfNewer(expected);
        order.verify(observability).completed(event.messageId(), expected, ProjectionUpdateResult.UPDATED);
    }

    @Test
    void recordsSkippedProjection() {
        var event = event(TelemetryEventVersions.V1);
        when(projection.updateIfNewer(any())).thenReturn(ProjectionUpdateResult.SKIPPED);

        service.handle(event, SOURCE);

        verify(observability).completed(eq(event.messageId()), any(),
            eq(ProjectionUpdateResult.SKIPPED));
    }

    @Test
    void projectionFailureIsObservedWithoutReachingCaller() {
        var event = event(TelemetryEventVersions.V1);
        var failure = new LatestStateProjectionException("Redis unavailable");
        when(projection.updateIfNewer(any())).thenThrow(failure);

        assertDoesNotThrow(() -> service.handle(event, SOURCE));

        verify(observability).failed(eq(event.messageId()), any(),
            same(failure));
        verify(writer).insert(any(TelemetrySampleEntity.class));
    }

    @Test
    void failedCommitPreventsProjection() {
        var failure = new TransactionSystemException("Commit failed");
        when(writer.insert(any())).thenThrow(failure);

        assertSame(failure, assertThrows(TransactionSystemException.class,
            () -> service.handle(event(TelemetryEventVersions.V1), SOURCE)));

        verifyNoInteractions(projection, observability);
    }

    @Test
    void doesNotPersistRejectedTelemetry() {
        TelemetryEvent event = event(TelemetryEventVersions.V1);
        when(eligibilityGuard.rejectIfIneligible(event, SOURCE)).thenReturn(true);

        service.handle(event, SOURCE);

        verify(writer, never()).insert(any(TelemetrySampleEntity.class));
        verifyNoInteractions(projection, observability);
    }

    @Test
    void rejectsUnsupportedVersion() {
        UnsupportedTelemetryEventVersionException exception =
            assertThrows(UnsupportedTelemetryEventVersionException.class,
                () -> service.handle(event(99), SOURCE));

        assertEquals(99, exception.actualVersion());
        verifyNoInteractions(writer);
    }

    @Test
    void rejectsNullEvent() {
        assertThrows(NullPointerException.class, () -> service.handle(null, SOURCE));
        verifyNoInteractions(writer);
    }

    @Test
    void completesNormallyForDuplicateMessageId() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("duplicate");

        when(writer.insert(any(TelemetrySampleEntity.class))).thenThrow(failure);
        when(failureClassifier.isDuplicateMessageId(failure)).thenReturn(true);

        assertDoesNotThrow(() -> service.handle(event(TelemetryEventVersions.V1), SOURCE));
        verifyNoInteractions(projection, observability);
    }

    @Test
    void propagatesNonDuplicateIntegrityFailure() {
        DataIntegrityViolationException failure =
            new DataIntegrityViolationException("foreign key");

        when(writer.insert(any(TelemetrySampleEntity.class))).thenThrow(failure);
        when(failureClassifier.isDuplicateMessageId(failure)).thenReturn(false);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
            () -> service.handle(event(TelemetryEventVersions.V1), SOURCE));

        assertSame(failure, thrown);
        verifyNoInteractions(projection, observability);
    }

    private static TelemetryEvent event(int version) {
        return new TelemetryEvent(version, UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), 42,
            Instant.parse("2026-08-01T10:15:30Z"), Instant.parse("2026-08-01T10:15:30.083Z"),
            new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964));
    }
}
