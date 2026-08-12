package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.TelemetryMessage;

import java.io.IOException;

public interface VehicleConnection extends AutoCloseable {
    void connect() throws IOException;

    void send(TelemetryMessage message) throws IOException;

    boolean isConnected();

    @Override
    void close();
}
