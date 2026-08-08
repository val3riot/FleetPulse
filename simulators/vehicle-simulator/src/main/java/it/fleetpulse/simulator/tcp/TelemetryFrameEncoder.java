package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.simulator.tcp.exception.TelemetryFrameEncodingException;
import it.fleetpulse.simulator.tcp.exception.UnsupportedProtocolVersionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public final class TelemetryFrameEncoder {

    private static final Logger log = LoggerFactory.getLogger(TelemetryFrameEncoder.class);

    private final ObjectMapper objectMapper;
    private final LengthPrefixedFrameWriter frameWriter;

    public TelemetryFrameEncoder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.frameWriter = new LengthPrefixedFrameWriter();
    }

    public void write(TelemetryMessage message, OutputStream output) throws IOException {
        validateArguments(message, output);
        validateProtocolVersion(message.protocolVersion());

        byte[] payload = objectMapper.writeValueAsBytes(message);
        frameWriter.write(payload, output);
        log.debug(
                "Encoded telemetry frame: messageId={}, payloadBytes={}",
                message.messageId(),
                payload.length
        );
    }

    private static void validateProtocolVersion(int protocolVersion)
            throws UnsupportedProtocolVersionException {
        if (protocolVersion != ProtocolConstants.PROTOCOL_VERSION) {
            throw new UnsupportedProtocolVersionException(protocolVersion);
        }
    }

    private static void validateArguments(
            TelemetryMessage message,
            OutputStream output
    ) throws TelemetryFrameEncodingException {
        if (message == null) {
            throw new TelemetryFrameEncodingException("Telemetry message must not be null");
        }
        if (output == null) {
            throw new TelemetryFrameEncodingException("Frame output stream must not be null");
        }
    }
}
