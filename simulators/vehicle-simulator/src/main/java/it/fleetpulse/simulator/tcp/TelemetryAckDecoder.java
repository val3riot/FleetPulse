package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import it.fleetpulse.simulator.tcp.exception.MalformedAcknowledgementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class TelemetryAckDecoder {

    private static final Logger log = LoggerFactory.getLogger(TelemetryAckDecoder.class);

    private final ObjectMapper objectMapper;

    public TelemetryAckDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public TelemetryAck read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] payload = LengthPrefixedFrameCodec.read(input);
        try {
            TelemetryAck ack = objectMapper.readValue(payload, TelemetryAck.class);
            TelemetryAcknowledgementValidator.validate(ack);
            log.debug("Decoded telemetry acknowledgement: messageId={}, status={}", ack.messageId(),
                ack.status());
            return ack;
        } catch (JacksonException exception) {
            throw new MalformedAcknowledgementException(exception);
        }
    }

}
