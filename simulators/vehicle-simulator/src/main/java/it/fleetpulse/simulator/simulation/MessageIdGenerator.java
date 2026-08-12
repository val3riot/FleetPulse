package it.fleetpulse.simulator.simulation;

import java.util.UUID;

@FunctionalInterface
public interface MessageIdGenerator {

    UUID next();
}
