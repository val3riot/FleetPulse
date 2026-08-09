package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

final class LengthPrefixedFrameWriter {

    void write(byte[] payload, OutputStream output) throws IOException {
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(output, "output must not be null");
        LengthPrefixedFrameCodec.write(payload, output);
    }
}
