package it.fleetpulse.protocol.frame;

public final class FrameTooLargeException extends InvalidFrameLengthException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public FrameTooLargeException(long frameLength) {
        super(frameLength);
    }
}
