package it.fleetpulse.gateway.tcp;

import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TelemetryAckEncoderTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TelemetryAckEncoder encoder = new TelemetryAckEncoder(objectMapper);
    @Test
    void writesLengthPrefixedAcknowledgementJson() throws Exception {
        TelemetryAck acknowledgement = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION,
                UUID.fromString(
                        "dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"
                ),
                AckStatus.ACCEPTED,
                Instant.parse("2026-08-01T10:15:30.083Z"),
                null
        );

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        encoder.write(acknowledgement, output);

        byte[] payload = LengthPrefixedFrameCodec.read(
                new ByteArrayInputStream(output.toByteArray())
        );

        TelemetryAck decoded = objectMapper.readValue(
                payload,
                TelemetryAck.class
        );

        assertEquals(acknowledgement, decoded);
    }

    @Test
    void rejectsNullAcknowledgement() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> encoder.write(
                        null,
                        new ByteArrayOutputStream()
                )
        );

        assertEquals(
                "acknowledgement must not be null",
                exception.getMessage()
        );
    }

    @Test
    void rejectsNullOutput() {
        TelemetryAck acknowledgement = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION,
                UUID.randomUUID(),
                AckStatus.ACCEPTED,
                Instant.EPOCH,
                null
        );

        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> encoder.write(acknowledgement, null)
        );

        assertEquals(
                "output must not be null",
                exception.getMessage()
        );
    }

}
