package it.fleetpulse.simulator.tcp;

import it.fleetpulse.simulator.tcp.exception.MalformedAcknowledgementException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryAckDecoderValidationTest {

    private final TelemetryAckDecoder decoder = new TelemetryAckDecoder(new ObjectMapper());

    @Test
    void rejectsNullObjectMapperAtConstruction() {
        NullPointerException exception =
            assertThrows(NullPointerException.class, () -> new TelemetryAckDecoder(null));

        assertEquals("objectMapper", exception.getMessage());
    }

    @Test
    void rejectsNullInputBeforeReading() {
        NullPointerException exception =
            assertThrows(NullPointerException.class, () -> decoder.read(null));

        assertEquals("input", exception.getMessage());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(MalformedAcknowledgementException.class, () -> decoder.read(frame("{")));
    }

    @Test
    void rejectsJsonNull() {
        assertThrows(MalformedAcknowledgementException.class, () -> decoder.read(frame("null")));
    }

    @Test
    void rejectsAcknowledgementWithoutRequiredFields() {
        assertThrows(MalformedAcknowledgementException.class,
            () -> decoder.read(frame("{\"protocolVersion\":1}")));
    }

    private ByteArrayInputStream frame(String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        byte[] frame =
            ByteBuffer.allocate(Integer.BYTES + payload.length).putInt(payload.length).put(payload)
                .array();
        return new ByteArrayInputStream(frame);
    }
}
