package it.fleetpulse.protocol.frame;

import java.io.EOFException;

public final class FrameStreamClosedException extends EOFException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public FrameStreamClosedException() {
        super("Stream closed before the next frame");
    }
}
