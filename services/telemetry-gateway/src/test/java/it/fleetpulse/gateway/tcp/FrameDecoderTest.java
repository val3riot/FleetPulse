package it.fleetpulse.gateway.tcp;

import it.fleetpulse.gateway.tcp.exception.InvalidTelemetryException;
import it.fleetpulse.gateway.tcp.exception.MalformedTelemetryException;
import it.fleetpulse.gateway.tcp.exception.UnsupportedProtocolVersionException;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.FrameStreamClosedException;
import it.fleetpulse.protocol.frame.FrameTooLargeException;
import it.fleetpulse.protocol.frame.InvalidFrameLengthException;
import it.fleetpulse.protocol.frame.TruncatedFrameHeaderException;
import it.fleetpulse.protocol.frame.TruncatedFramePayloadException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FrameDecoderTest {

    private static final String VALID_MESSAGE =
        "{\"protocolVersion\":1,\"messageId\":\"dc0fc799-0913-4e72-bd2d-8ee8ccf52e22\"," +
            "\"vehicleId\":\"97e194a8-64b3-4885-b1e6-25fd482f58c0\",\"sequenceNumber\":42," +
            "\"observedAt\":\"2026-08-01T10:15:30Z\",\"speedKmh\":72.4,\"engineTemperatureC\":91" +
            ".8,\"batteryVoltage\":12.6,\"odometerKm\":85312,\"latitude\":41.9028," +
            "\"longitude\":12.4964}";

    private final FrameDecoder decoder = new FrameDecoder(new ObjectMapper());

    @Test
    void decodesFrameFromFragmentedReads() throws Exception {
        TelemetryMessage message = decoder.read(oneByteAtATime(frame(VALID_MESSAGE)));

        assertEquals(42, message.sequenceNumber());
    }

    @Test
    void decodesConsecutiveFramesFromPersistentStream() throws Exception {
        byte[] first = frameBytes(VALID_MESSAGE);
        byte[] second =
            frameBytes(VALID_MESSAGE.replace("\"sequenceNumber\":42", "\"sequenceNumber\":43"));
        ByteArrayInputStream input = new ByteArrayInputStream(
            ByteBuffer.allocate(first.length + second.length).put(first).put(second).array());

        assertEquals(42, decoder.read(input).sequenceNumber());
        assertEquals(43, decoder.read(input).sequenceNumber());
    }

    @Test
    void rejectsInvalidUnsignedLengthsBeforeAllocatingPayload() {
        assertThrows(InvalidFrameLengthException.class, () -> decoder.read(header(0)));
        assertThrows(FrameTooLargeException.class,
            () -> decoder.read(header(ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES + 1)));
        assertThrows(FrameTooLargeException.class, () -> decoder.read(header(0x8000_0000)));
    }

    @Test
    void distinguishesClosedStreamAndTruncatedFrameParts() {
        assertThrows(FrameStreamClosedException.class,
            () -> decoder.read(new ByteArrayInputStream(new byte[0])));

        TruncatedFrameHeaderException headerException =
            assertThrows(TruncatedFrameHeaderException.class,
                () -> decoder.read(new ByteArrayInputStream(new byte[]{0, 0, 1})));
        assertEquals(3, headerException.bytesRead());

        byte[] truncatedPayload =
            ByteBuffer.allocate(6).putInt(3).put(new byte[]{'{', '}'}).array();
        TruncatedFramePayloadException payloadException =
            assertThrows(TruncatedFramePayloadException.class,
                () -> decoder.read(new ByteArrayInputStream(truncatedPayload)));
        assertEquals(3, payloadException.expectedBytes());
        assertEquals(2, payloadException.bytesRead());
    }

    @Test
    void rejectsMalformedJsonAndMissingRequiredFields() {
        assertThrows(MalformedTelemetryException.class, () -> decoder.read(frame("{")));
        assertThrows(MalformedTelemetryException.class, () -> decoder.read(frame("null")));
        assertThrows(MalformedTelemetryException.class,
            () -> decoder.read(frame("{\"protocolVersion\":1}")));
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        String message = VALID_MESSAGE.replace("\"protocolVersion\":1", "\"protocolVersion\":2");

        assertThrows(UnsupportedProtocolVersionException.class, () -> decoder.read(frame(message)));
    }

    @Test
    void rejectsInvalidTelemetryValues() {
        String message = VALID_MESSAGE.replace("\"latitude\":41.9028", "\"latitude\":91");

        assertThrows(InvalidTelemetryException.class, () -> decoder.read(frame(message)));
    }

    @Test
    void rejectsEachMissingRequiredNumericField() throws Exception {
        for (String field : new String[]{"protocolVersion",
            "sequenceNumber",
            "speedKmh",
            "engineTemperatureC",
            "batteryVoltage",
            "odometerKm",
            "latitude",
            "longitude"}) {
            ObjectNode payload = (ObjectNode) new ObjectMapper().readTree(VALID_MESSAGE);
            payload.remove(field);

            assertThrows(MalformedTelemetryException.class,
                () -> decoder.read(frame(payload.toString())), field);
        }
    }

    @Test
    void rejectsNullDependenciesAndInput() {
        assertEquals("objectMapper",
            assertThrows(NullPointerException.class, () -> new FrameDecoder(null)).getMessage());
        assertEquals("input",
            assertThrows(NullPointerException.class, () -> decoder.read(null)).getMessage());
    }

    private ByteArrayInputStream frame(String json) {
        return new ByteArrayInputStream(frameBytes(json));
    }

    private byte[] frameBytes(String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + payload.length).putInt(payload.length)
            .put(payload).array();
    }

    private ByteArrayInputStream header(int length) {
        return new ByteArrayInputStream(ByteBuffer.allocate(Integer.BYTES).putInt(length).array());
    }

    private InputStream oneByteAtATime(InputStream input) {
        return new FilterInputStream(input) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
    }
}
