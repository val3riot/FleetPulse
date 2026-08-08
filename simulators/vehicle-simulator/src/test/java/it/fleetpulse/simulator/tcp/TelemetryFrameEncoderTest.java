package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.simulator.tcp.exception.TelemetryFrameEncodingException;
import it.fleetpulse.simulator.tcp.exception.UnsupportedProtocolVersionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryFrameEncoderTest {

    private static final TelemetryMessage MESSAGE = new TelemetryMessage(
            ProtocolConstants.PROTOCOL_VERSION,
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
            42,
            Instant.parse("2026-08-01T10:15:30Z"),
            72.4,
            91.8,
            12.6,
            85312,
            41.9028,
            12.4964
    );

    @Test
    void writesDocumentedGoldenFrame() throws Exception {
        String expectedJson = "{\"protocolVersion\":1,\"messageId\":\"dc0fc799-0913-4e72-bd2d-8ee8ccf52e22\","
                + "\"vehicleId\":\"97e194a8-64b3-4885-b1e6-25fd482f58c0\",\"sequenceNumber\":42,"
                + "\"observedAt\":\"2026-08-01T10:15:30Z\",\"speedKmh\":72.4,"
                + "\"engineTemperatureC\":91.8,\"batteryVoltage\":12.6,\"odometerKm\":85312,"
                + "\"latitude\":41.9028,\"longitude\":12.4964}";
        byte[] payload = expectedJson.getBytes(StandardCharsets.UTF_8);
        byte[] expectedFrame = ByteBuffer.allocate(Integer.BYTES + payload.length)
                .putInt(payload.length)
                .put(payload)
                .array();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        new TelemetryFrameEncoder(new ObjectMapper()).write(MESSAGE, output);

        assertArrayEquals(expectedFrame, output.toByteArray());
    }

    @Test
    void rejectsNullArguments() {
        TelemetryFrameEncoder encoder = new TelemetryFrameEncoder(new ObjectMapper());

        assertThrows(TelemetryFrameEncodingException.class, () -> encoder.write(null, new ByteArrayOutputStream()));
        assertThrows(TelemetryFrameEncodingException.class, () -> encoder.write(MESSAGE, null));
    }

    @Test
    void rejectsNullObjectMapperAtConstruction() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new TelemetryFrameEncoder(null)
        );

        assertEquals("objectMapper", exception.getMessage());
    }

    @Test
    void rejectsUnsupportedOutboundProtocolVersionBeforeSerialization() {
        TelemetryMessage unsupportedMessage = new TelemetryMessage(
                99,
                MESSAGE.messageId(),
                MESSAGE.vehicleId(),
                MESSAGE.sequenceNumber(),
                MESSAGE.observedAt(),
                MESSAGE.speedKmh(),
                MESSAGE.engineTemperatureC(),
                MESSAGE.batteryVoltage(),
                MESSAGE.odometerKm(),
                MESSAGE.latitude(),
                MESSAGE.longitude()
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        UnsupportedProtocolVersionException exception = assertThrows(
                UnsupportedProtocolVersionException.class,
                () -> new TelemetryFrameEncoder(new ObjectMapper()).write(unsupportedMessage, output)
        );

        assertEquals(99, exception.protocolVersion());
        assertEquals(0, output.size());
    }

    @Test
    void doesNotCloseCallerOutputStream() throws Exception {
        TrackingOutputStream output = new TrackingOutputStream();

        new TelemetryFrameEncoder(new ObjectMapper()).write(MESSAGE, output);

        assertFalse(output.closed);
    }

    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
