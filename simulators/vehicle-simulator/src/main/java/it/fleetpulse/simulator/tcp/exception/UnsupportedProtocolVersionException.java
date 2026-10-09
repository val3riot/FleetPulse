package it.fleetpulse.simulator.tcp.exception;

import it.fleetpulse.protocol.ProtocolConstants;

import java.io.IOException;

public final class UnsupportedProtocolVersionException extends IOException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final int protocolVersion;

    public UnsupportedProtocolVersionException(int protocolVersion) {
        super("Unsupported protocol version " + protocolVersion + "; expected " +
                ProtocolConstants.PROTOCOL_VERSION);
        this.protocolVersion = protocolVersion;
    }

    public int protocolVersion() {
        return protocolVersion;
    }
}
