package it.fleetpulse.protocol.frame;

import it.fleetpulse.protocol.ProtocolConstants;

import java.io.EOFException;

public final class TruncatedFrameHeaderException extends EOFException {

    private final int bytesRead;

    public TruncatedFrameHeaderException(int bytesRead) {
        super("Truncated frame header: " + bytesRead + "/"
                + ProtocolConstants.HEADER_SIZE_BYTES + " bytes");
        this.bytesRead = bytesRead;
    }

    public int bytesRead() {
        return bytesRead;
    }
}
