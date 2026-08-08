package it.fleetpulse.simulator.tcp.exception;

import it.fleetpulse.protocol.ProtocolConstants;

import java.io.EOFException;

public final class TruncatedAcknowledgementHeaderException extends EOFException {

    private final int bytesRead;

    public TruncatedAcknowledgementHeaderException(int bytesRead) {
        super("Truncated acknowledgement header: " + bytesRead + "/"
                + ProtocolConstants.HEADER_SIZE_BYTES + " bytes");
        this.bytesRead = bytesRead;
    }

    public int bytesRead() {
        return bytesRead;
    }
}
