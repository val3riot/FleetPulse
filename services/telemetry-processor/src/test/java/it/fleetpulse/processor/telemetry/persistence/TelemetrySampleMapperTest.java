package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class TelemetrySampleMapperTest {

    private static final UUID MESSAGE_ID =
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");

    private static final UUID VEHICLE_ID =
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-01T10:15:30Z");

    private static final Instant RECEIVED_AT =
            Instant.parse("2026-08-01T10:15:30.083Z");

    private static final Instant PROCESSED_AT =
            Instant.parse("2026-08-01T10:15:30.150Z");

    private final TelemetrySampleMapper mapper =
            new TelemetrySampleMapper();

    @Test
    void mapsCompleteVersionOneEvent() {
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEventVersions.V1,
                MESSAGE_ID,
                VEHICLE_ID,
                42,
                OBSERVED_AT,
                RECEIVED_AT,
                new TelemetryData(
                        72.4,
                        91.8,
                        12.6,
                        85_312,
                        41.9028,
                        12.4964
                )
        );

        TelemetrySampleEntity entity =
                mapper.toEntity(event, PROCESSED_AT);

        assertAll(
                () -> assertNull(entity.getId()),
                () -> assertEquals(MESSAGE_ID, entity.getMessageId()),
                () -> assertEquals(VEHICLE_ID, entity.getVehicleId()),
                () -> assertEquals(42, entity.getSequenceNumber()),
                () -> assertEquals(OBSERVED_AT, entity.getObservedAt()),
                () -> assertEquals(RECEIVED_AT, entity.getReceivedAt()),
                () -> assertEquals(PROCESSED_AT, entity.getProcessedAt()),
                () -> assertEquals(72.4, entity.getSpeedKmh()),
                () -> assertEquals(
                        91.8,
                        entity.getEngineTemperatureC()
                ),
                () -> assertEquals(
                        12.6,
                        entity.getBatteryVoltage()
                ),
                () -> assertEquals(85_312, entity.getOdometerKm()),
                () -> assertEquals(41.9028, entity.getLatitude()),
                () -> assertEquals(12.4964, entity.getLongitude())
        );
    }
}
