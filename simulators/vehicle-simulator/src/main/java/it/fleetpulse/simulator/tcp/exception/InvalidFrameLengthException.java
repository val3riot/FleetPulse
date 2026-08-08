package it.fleetpulse.simulator.tcp.exception;

import it.fleetpulse.protocol.ProtocolConstants;

import java.io.IOException;

public final class InvalidFrameLengthException extends IOException {

    private final long frameLength;

    public InvalidFrameLengthException(long frameLength) {
        super("Frame length must be between 1 and "
                + ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES
                + " bytes, but was " + frameLength);
        this.frameLength = frameLength;
    }

    public long frameLength() {
        return frameLength;
    }
}
