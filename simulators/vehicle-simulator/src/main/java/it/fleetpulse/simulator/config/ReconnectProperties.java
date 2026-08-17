package it.fleetpulse.simulator.config;

import java.time.Duration;

public record ReconnectProperties(
        Duration initialBackoff,
        Duration maxBackoff,
        int maxAttempts,
        double jitterRatio
) {

    public ReconnectProperties {
        requirePositive(initialBackoff, "reconnect.initialBackoff");
        requirePositive(maxBackoff, "reconnect.maxBackoff");
        if (initialBackoff.compareTo(maxBackoff) > 0) {
            throw new IllegalArgumentException(
                "reconnect.initialBackoff must not exceed maxBackoff");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("reconnect.maxAttempts must be greater than zero");
        }
        if (!Double.isFinite(jitterRatio) || jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("reconnect.jitterRatio must be between 0 and 1");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(property + " must be greater than zero");
        }
    }
}
