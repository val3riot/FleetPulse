package it.fleetpulse.protocol;

public final class ProtocolConstants {
    public static final int PROTOCOL_VERSION = 1;
    public static final int HEADER_SIZE_BYTES = 4;
    public static final int MAX_PAYLOAD_SIZE_BYTES = 64 * 1024;

    private ProtocolConstants() {
    }
}
