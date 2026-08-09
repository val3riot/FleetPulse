package it.fleetpulse.gateway.tcp.exception;

import it.fleetpulse.protocol.ProtocolConstants;

import java.io.IOException;

public final class UnsupportedProtocolVersionException extends IOException {

    private final int protocolVersion;

    public UnsupportedProtocolVersionException(int protocolVersion) {
        super("Unsupported protocol version " + protocolVersion
                + "; expected " + ProtocolConstants.PROTOCOL_VERSION);
        this.protocolVersion = protocolVersion;
    }

    public int protocolVersion() {
        return protocolVersion;
    }
}
