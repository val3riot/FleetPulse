package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.frame.InvalidFrameLengthException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LengthPrefixedFrameWriterTest {

    private final LengthPrefixedFrameWriter writer = new LengthPrefixedFrameWriter();

    @Test
    void usesUtf8ByteLengthInBigEndianHeader() throws Exception {
        byte[] payload = "{\"value\":\"è\"}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        writer.write(payload, output);

        byte[] frame = output.toByteArray();
        assertEquals(payload.length, ByteBuffer.wrap(frame).getInt());
        assertArrayEquals(payload, Arrays.copyOfRange(frame, Integer.BYTES, frame.length));
    }

    @Test
    void rejectsEmptyAndOversizedPayloads() {
        assertThrows(
                InvalidFrameLengthException.class,
                () -> writer.write(new byte[0], new ByteArrayOutputStream())
        );
        assertThrows(
                InvalidFrameLengthException.class,
                () -> writer.write(
                        new byte[ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES + 1],
                        new ByteArrayOutputStream()
                )
        );
    }

    @Test
    void rejectsNullArguments() {
        NullPointerException nullPayload = assertThrows(
                NullPointerException.class,
                () -> writer.write(null, new ByteArrayOutputStream())
        );
        NullPointerException nullOutput = assertThrows(
                NullPointerException.class,
                () -> writer.write(new byte[]{1}, null)
        );

        assertEquals("payload must not be null", nullPayload.getMessage());
        assertEquals("output must not be null", nullOutput.getMessage());
    }
}
