package it.fleetpulse.simulator.simulation;

public interface VehicleTask extends Runnable, AutoCloseable {

    @Override
    void close();
}
