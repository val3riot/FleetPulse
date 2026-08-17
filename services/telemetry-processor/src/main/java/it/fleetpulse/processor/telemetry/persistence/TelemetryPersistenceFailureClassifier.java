package it.fleetpulse.processor.telemetry.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class TelemetryPersistenceFailureClassifier {

    private static final String MESSAGE_ID_UNIQUE_CONSTRAINT = "uq_telemetry_samples_message_id";

    public boolean isDuplicateMessageId(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");

        Throwable current = failure;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation &&
                MESSAGE_ID_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
                return true;
            }

            current = current.getCause();
        }

        return false;
    }
}
