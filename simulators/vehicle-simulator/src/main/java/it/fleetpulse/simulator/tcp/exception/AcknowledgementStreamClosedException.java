package it.fleetpulse.simulator.tcp.exception;

import java.io.EOFException;

public final class AcknowledgementStreamClosedException extends EOFException {

    public AcknowledgementStreamClosedException() {
        super("Stream closed before the next acknowledgement frame");
    }
}
