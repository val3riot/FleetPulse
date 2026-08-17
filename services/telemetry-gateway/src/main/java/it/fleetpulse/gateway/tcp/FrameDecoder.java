package it.fleetpulse.gateway.tcp;

import it.fleetpulse.gateway.tcp.exception.MalformedTelemetryException;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class FrameDecoder {

    private static final Logger log = LoggerFactory.getLogger(FrameDecoder.class);

    private final ObjectMapper objectMapper;

    public FrameDecoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public TelemetryMessage read(InputStream input) throws IOException {
        Objects.requireNonNull(input, "input");
        byte[] payload = LengthPrefixedFrameCodec.read(input);

        try {
            JsonNode payloadTree = objectMapper.readTree(payload);
            TelemetryMessageValidator.validateRequiredNumericFields(payloadTree);
            TelemetryMessage message = objectMapper.readValue(payload, TelemetryMessage.class);
            TelemetryMessageValidator.validate(message);
            log.debug("Decoded telemetry frame: messageId={}, vehicleId={}, payloadBytes={}",
                message.messageId(), message.vehicleId(), payload.length);
            return message;
        } catch (JacksonException exception) {
            throw new MalformedTelemetryException(exception);
        }
    }

}
