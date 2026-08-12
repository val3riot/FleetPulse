package it.fleetpulse.simulator.simulation.reconnect;

import java.time.Duration;
import java.util.Objects;

public final class ExponentialBackoff {

    private final Duration initialDelay;
    private final Duration maximumDelay;
    private Duration nextDelay;

    public ExponentialBackoff(Duration initialDelay, Duration maximumDelay) {
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maximumDelay = requirePositive(maximumDelay, "maximumDelay");
        if (initialDelay.compareTo(maximumDelay) > 0) {
            throw new IllegalArgumentException("initialDelay must not exceed maximumDelay");
        }
        this.nextDelay = initialDelay;
    }

    public Duration nextDelay() {
        Duration delay = nextDelay;
        nextDelay = doubledAndCapped(delay);
        return delay;
    }

    public void reset() {
        nextDelay = initialDelay;
    }

    private Duration doubledAndCapped(Duration delay) {
        if (delay.compareTo(maximumDelay.dividedBy(2)) > 0) {
            return maximumDelay;
        }
        Duration doubled = delay.multipliedBy(2);
        return doubled.compareTo(maximumDelay) > 0 ? maximumDelay : doubled;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return duration;
    }
}
