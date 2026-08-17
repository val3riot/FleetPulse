package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.frame.FrameStreamClosedException;
import it.fleetpulse.protocol.frame.TruncatedFrameHeaderException;
import it.fleetpulse.protocol.frame.TruncatedFramePayloadException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryAckDecoderEofTest {

    private final TelemetryAckDecoder decoder = new TelemetryAckDecoder(new ObjectMapper());

    @Test
    void reportsStreamClosedBeforeHeader() {
        assertThrows(FrameStreamClosedException.class,
            () -> decoder.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void reportsTruncatedHeader() {
        TruncatedFrameHeaderException exception = assertThrows(TruncatedFrameHeaderException.class,
            () -> decoder.read(new ByteArrayInputStream(new byte[]{0, 0, 1})));

        assertEquals(3, exception.bytesRead());
    }

    @Test
    void reportsTruncatedPayload() {
        byte[] frame = ByteBuffer.allocate(6).putInt(3).put(new byte[]{'{', '}'}).array();

        TruncatedFramePayloadException exception =
            assertThrows(TruncatedFramePayloadException.class,
                () -> decoder.read(new ByteArrayInputStream(frame)));

        assertEquals(3, exception.expectedBytes());
        assertEquals(2, exception.bytesRead());
    }
}
