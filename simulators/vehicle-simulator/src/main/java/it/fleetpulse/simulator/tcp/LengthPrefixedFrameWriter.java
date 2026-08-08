package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.simulator.tcp.exception.InvalidFrameLengthException;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

final class LengthPrefixedFrameWriter {

    void write(byte[] payload, OutputStream output) throws IOException {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(output, "output must not be null");
        validatePayloadLength(payload.length);

        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(payload.length);
        data.write(payload);
        data.flush();
    }

    private static void validatePayloadLength(int length) throws InvalidFrameLengthException {
        if (length == 0 || length > ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES) {
            throw new InvalidFrameLengthException(length);
        }
    }
}
