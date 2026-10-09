package it.fleetpulse.protocol.frame;

import it.fleetpulse.protocol.ProtocolConstants;

import java.io.IOException;

public class InvalidFrameLengthException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final long frameLength;

    public InvalidFrameLengthException(long frameLength) {
        super("Frame length must be between 1 and " + ProtocolConstants.MAX_PAYLOAD_SIZE_BYTES +
                " bytes, but was " + frameLength);
        this.frameLength = frameLength;
    }

    public long frameLength() {
        return frameLength;
    }
}
