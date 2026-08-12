package it.fleetpulse.simulator.simulation.reconnect;

import java.time.Duration;

@FunctionalInterface
public interface RetrySleeper {

    void sleep(Duration duration) throws InterruptedException;
}
