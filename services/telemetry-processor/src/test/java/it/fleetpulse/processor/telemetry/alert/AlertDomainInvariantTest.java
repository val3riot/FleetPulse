package it.fleetpulse.processor.telemetry.alert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertDomainInvariantTest {
    private static final UUID ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsNonFiniteTelemetry(double value) {
        assertThatThrownBy(() -> new AlertTelemetrySample(ID, ID, value, 12.6, 0))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertTelemetrySample(ID, ID, 90.0, value, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDescriptionsThatCannotBePersisted() {
        assertThatThrownBy(() -> new AlertCandidate(ID, ID, AlertType.SERVICE_DUE,
            AlertSeverity.MEDIUM, " "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AlertCandidate(ID, ID, AlertType.SERVICE_DUE,
            AlertSeverity.MEDIUM, "x".repeat(AlertCandidate.MAX_DESCRIPTION_LENGTH + 1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
