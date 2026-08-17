package it.fleetpulse.gateway.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TelemetryEventMapperTest {
    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");

    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-01T10:15:30Z");

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-01T10:15:30.083Z");

    private final TelemetryEventMapper mapper =
        new TelemetryEventMapper(Clock.fixed(RECEIVED_AT, ZoneOffset.UTC));

    @Test
    void mapsTelemetryMessageToVersionOneEvent() {
        TelemetryMessage message =
            new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION, MESSAGE_ID, VEHICLE_ID, 42,
                OBSERVED_AT, 72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
        TelemetryEvent result = mapper.map(message);
        TelemetryEvent excepted =
            new TelemetryEvent(1, MESSAGE_ID, VEHICLE_ID, 42, OBSERVED_AT, RECEIVED_AT,
                new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964));
        assertEquals(excepted, result);
    }

    @Test
    void rejectsNullMessage() {
        NullPointerException exception =
            assertThrows(NullPointerException.class, () -> mapper.map(null));
        assertEquals("message must not be null", exception.getMessage());
    }

    @Test
    void rejectsNullClock() {
        NullPointerException exception =
            assertThrows(NullPointerException.class, () -> new TelemetryEventMapper(null));
        assertEquals("clock must not be null", exception.getMessage());
    }
}
