package it.fleetpulse.processor.telemetry.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class TelemetryPersistenceFailureClassifier {

    private static final String MESSAGE_ID_UNIQUE_CONSTRAINT = "uq_telemetry_samples_message_id";
    private static final String ALERT_SOURCE_TYPE_UNIQUE_CONSTRAINT =
        "uq_maintenance_alerts_source_message_type";

    public boolean isDuplicateMessageId(Throwable failure) {
        return hasConstraint(failure, MESSAGE_ID_UNIQUE_CONSTRAINT);
    }

    public boolean isDuplicateAlertSourceType(Throwable failure) {
        return hasConstraint(failure, ALERT_SOURCE_TYPE_UNIQUE_CONSTRAINT);
    }

    private boolean hasConstraint(Throwable failure, String expectedConstraint) {
        Objects.requireNonNull(failure, "failure must not be null");

        Throwable current = failure;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation &&
                expectedConstraint.equals(violation.getConstraintName())) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
