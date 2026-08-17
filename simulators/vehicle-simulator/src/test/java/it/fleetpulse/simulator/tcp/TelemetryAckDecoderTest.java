package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.ProtocolErrorCode;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.frame.InvalidFrameLengthException;
import it.fleetpulse.simulator.tcp.exception.UnsupportedProtocolVersionException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryAckDecoderTest {

    private static final String ACCEPTED_ACK =
        "{\"protocolVersion\":1,\"messageId\":\"dc0fc799-0913-4e72-bd2d-8ee8ccf52e22\"," +
            "\"status\":\"ACCEPTED\",\"receivedAt\":\"2026-08-01T10:15:30.083Z\"}";
    private static final String REJECTED_ACK =
        "{\"protocolVersion\":1,\"messageId\":\"dc0fc799-0913-4e72-bd2d-8ee8ccf52e22\"," +
            "\"status\":\"REJECTED\",\"receivedAt\":\"2026-08-01T10:15:30.083Z\"," +
            "\"errorCode\":\"UPSTREAM_UNAVAILABLE\"}";

    private final TelemetryAckDecoder decoder = new TelemetryAckDecoder(new ObjectMapper());

    @Test
    void decodesAcceptedAcknowledgementFromFragmentedReads() throws Exception {
        TelemetryAck acknowledgement = decoder.read(oneByteAtATime(frame(ACCEPTED_ACK)));

        assertEquals(AckStatus.ACCEPTED, acknowledgement.status());
        assertNull(acknowledgement.errorCode());
    }

    @Test
    void decodesRejectedAcknowledgement() throws Exception {
        TelemetryAck acknowledgement = decoder.read(frame(REJECTED_ACK));

        assertEquals(AckStatus.REJECTED, acknowledgement.status());
        assertEquals(ProtocolErrorCode.UPSTREAM_UNAVAILABLE, acknowledgement.errorCode());
    }

    @Test
    void decodesConsecutiveAcknowledgementsFromPersistentStream() throws Exception {
        byte[] first = frameBytes(ACCEPTED_ACK);
        byte[] second = frameBytes(REJECTED_ACK);
        ByteArrayInputStream input = new ByteArrayInputStream(
            ByteBuffer.allocate(first.length + second.length).put(first).put(second).array());

        assertEquals(AckStatus.ACCEPTED, decoder.read(input).status());
        assertEquals(AckStatus.REJECTED, decoder.read(input).status());
    }

    @Test
    void rejectsInvalidUnsignedLengths() {
        assertThrows(InvalidFrameLengthException.class, () -> decoder.read(header(0)));
        assertThrows(InvalidFrameLengthException.class,
            () -> decoder.read(header(ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES + 1)));
        assertThrows(InvalidFrameLengthException.class, () -> decoder.read(header(0x8000_0000)));
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        String acknowledgement =
            ACCEPTED_ACK.replace("\"protocolVersion\":1", "\"protocolVersion\":2");

        assertThrows(UnsupportedProtocolVersionException.class,
            () -> decoder.read(frame(acknowledgement)));
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
