package it.fleetpulse.gateway.tcp;

import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public class TelemetryAckEncoder {
    private final ObjectMapper objectMapper;

    public TelemetryAckEncoder(ObjectMapper objectMapper){
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper must not be null"
        );
    }
    public void write(
            TelemetryAck acknowledgement,
            OutputStream output
    ) throws IOException {
        Objects.requireNonNull(
                acknowledgement,
                "acknowledgement must not be null"
        );
        Objects.requireNonNull(
                output,
                "output must not be null"
        );
        byte[] payload = objectMapper.writeValueAsBytes(acknowledgement);
        LengthPrefixedFrameCodec.write(payload, output);
    }
}
