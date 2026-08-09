package it.fleetpulse.protocol.frame;

public final class FrameTooLargeException extends InvalidFrameLengthException {

    public FrameTooLargeException(long frameLength) {
        super(frameLength);
    }
}
