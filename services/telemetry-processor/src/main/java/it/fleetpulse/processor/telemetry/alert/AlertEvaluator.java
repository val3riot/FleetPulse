package it.fleetpulse.processor.telemetry.alert;

import java.util.List;
import java.util.Objects;

public final class AlertEvaluator {
    private final List<AlertRule> rules;

    public AlertEvaluator(List<AlertRule> rules) {
        Objects.requireNonNull(rules, "rules must not be null");
        if (rules.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("rules must not contain null elements");
        }
        this.rules = List.copyOf(rules);
    }

    public List<AlertCandidate> evaluate(AlertVehicle vehicle, AlertTelemetrySample sample) {
        Objects.requireNonNull(vehicle, "vehicle must not be null");
        Objects.requireNonNull(sample, "sample must not be null");
        return rules.stream().flatMap(rule -> rule.evaluate(vehicle, sample).stream()).toList();
    }
}
