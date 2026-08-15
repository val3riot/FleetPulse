package it.fleetpulse.processor.telemetry;

public final class UnsupportedTelemetryEventVersionException extends RuntimeException {

    private final int actualVersion;

    public UnsupportedTelemetryEventVersionException(int actualVersion) {
        super("Unsupported telemetry event version: " + actualVersion);
        this.actualVersion = actualVersion;
    }

    public int actualVersion() {
        return actualVersion;
    }
}
