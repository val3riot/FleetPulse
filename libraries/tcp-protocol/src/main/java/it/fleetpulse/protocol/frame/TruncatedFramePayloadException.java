package it.fleetpulse.protocol.frame;

import java.io.EOFException;

public final class TruncatedFramePayloadException extends EOFException {

    private final int expectedBytes;
    private final int bytesRead;

    public TruncatedFramePayloadException(int expectedBytes, int bytesRead) {
        super("Truncated frame payload: " + bytesRead + "/" + expectedBytes + " bytes");
        this.expectedBytes = expectedBytes;
        this.bytesRead = bytesRead;
    }

    public int expectedBytes() {
        return expectedBytes;
    }

    public int bytesRead() {
        return bytesRead;
    }
}
