package it.fleetpulse.processor.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleEntity;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleMapper;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class TelemetryEventProcessingServiceTest {
    private static final Instant PROCESSED_AT =
            Instant.parse("2026-08-01T10:15:30.150Z");

    private final TelemetrySampleRepository repository =
            mock(TelemetrySampleRepository.class);

    private final TelemetryEventProcessingService service =
            new TelemetryEventProcessingService(
                    repository,
                    new TelemetrySampleMapper(),
                    Clock.fixed(PROCESSED_AT, ZoneOffset.UTC)
            );

    @BeforeEach
    void returnEntityBeingSaved() {
        when(repository.saveAndFlush(any(TelemetrySampleEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsVersionOne() {
        TelemetryEvent event = event(TelemetryEventVersions.V1);

        service.handle(event);

        ArgumentCaptor<TelemetrySampleEntity> captor =
                ArgumentCaptor.forClass(TelemetrySampleEntity.class);

        verify(repository).saveAndFlush(captor.capture());

        TelemetrySampleEntity saved = captor.getValue();

        assertEquals(event.messageId(), saved.getMessageId());
        assertEquals(event.vehicleId(), saved.getVehicleId());
        assertEquals(PROCESSED_AT, saved.getProcessedAt());
    }

    @Test
    void rejectsUnsupportedVersion() {
        UnsupportedTelemetryEventVersionException exception = assertThrows(
                UnsupportedTelemetryEventVersionException.class,
                () -> service.handle(event(99))
        );

        assertEquals(99, exception.actualVersion());
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsNullEvent() {
        assertThrows(NullPointerException.class, () -> service.handle(null));
        verifyNoInteractions(repository);
    }

    private static TelemetryEvent event(int version) {
        return new TelemetryEvent(
                version,
                UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                42,
                Instant.parse("2026-08-01T10:15:30Z"),
                Instant.parse("2026-08-01T10:15:30.083Z"),
                new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964)
        );
    }
}
