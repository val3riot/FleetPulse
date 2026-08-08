package it.fleetpulse.simulator.tcp.exception;

import java.io.IOException;

public final class MalformedAcknowledgementException extends IOException {

    public MalformedAcknowledgementException(String message) {
        super(message);
    }

    public MalformedAcknowledgementException(Throwable cause) {
        super("Acknowledgement payload is malformed or cannot be decoded", cause);
    }
}
