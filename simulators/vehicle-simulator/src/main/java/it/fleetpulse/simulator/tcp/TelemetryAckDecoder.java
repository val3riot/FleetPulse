package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.simulator.tcp.exception.AcknowledgementStreamClosedException;
import it.fleetpulse.simulator.tcp.exception.InvalidFrameLengthException;
import it.fleetpulse.simulator.tcp.exception.MalformedAcknowledgementException;
import it.fleetpulse.simulator.tcp.exception.TruncatedAcknowledgementHeaderException;
import it.fleetpulse.simulator.tcp.exception.TruncatedAcknowledgementPayloadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

public final class TelemetryAckDecoder {

    private static final Logger log = LoggerFactory.getLogger(TelemetryAckDecoder.class);

    private final ObjectMapper objectMapper;

    public TelemetryAckDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public TelemetryAck read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] header = readHeader(input);
        int rawLength = ByteBuffer
                .wrap(header)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
        long length = Integer.toUnsignedLong(rawLength);
        validatePayloadLength(length);
        byte[] payload = new byte[(int) length];
        int payloadBytesRead = readFully(input, payload);
        if (payloadBytesRead < payload.length) {
            throw new TruncatedAcknowledgementPayloadException(
                    payload.length,
                    payloadBytesRead
            );
        }
        try {
            TelemetryAck ack = objectMapper.readValue(payload, TelemetryAck.class);
            TelemetryAcknowledgementValidator.validate(ack);
            log.debug(
                    "Decoded telemetry acknowledgement: messageId={}, status={}",
                    ack.messageId(),
                    ack.status()
            );
            return ack;
        } catch (JacksonException exception) {
            throw new MalformedAcknowledgementException(exception);
        }
    }

    private static void validatePayloadLength(long length) throws InvalidFrameLengthException {
        if (length == 0 || length > ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES) {
            throw new InvalidFrameLengthException(length);
        }
    }

    private static byte[] readHeader(InputStream input) throws IOException {
        byte[] header =
                new byte[ProtocolConstants.HEADER_SIZE_BYTES];

        int read = readFully(input, header);

        if (read == 0) {
            throw new AcknowledgementStreamClosedException();
        }

        if (read < header.length) {
            throw new TruncatedAcknowledgementHeaderException(read);
        }

        return header;
    }
    private static int readFully(
            InputStream input,
            byte[] buffer
    ) throws IOException {

        int offset = 0;

        while (offset < buffer.length) {
            int count = input.read(
                    buffer,
                    offset,
                    buffer.length - offset
            );

            if (count == -1) {
                break;
            }

            offset += count;
        }

        return offset;
    }
}
