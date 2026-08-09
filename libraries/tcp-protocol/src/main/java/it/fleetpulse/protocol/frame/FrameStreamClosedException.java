package it.fleetpulse.protocol.frame;

import java.io.EOFException;

public final class FrameStreamClosedException extends EOFException {

    public FrameStreamClosedException() {
        super("Stream closed before the next frame");
    }
}
