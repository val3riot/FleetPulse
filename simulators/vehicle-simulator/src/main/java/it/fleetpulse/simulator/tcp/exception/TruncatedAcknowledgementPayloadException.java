package it.fleetpulse.simulator.tcp.exception;

import java.io.EOFException;

public final class TruncatedAcknowledgementPayloadException extends EOFException {

    private final int expectedBytes;
    private final int bytesRead;

    public TruncatedAcknowledgementPayloadException(int expectedBytes, int bytesRead) {
        super("Truncated acknowledgement payload: " + bytesRead + "/" + expectedBytes + " bytes");
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
