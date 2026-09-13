package it.fleetpulse.processor.telemetry.persistence;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryPersistenceFailureClassifierTest {
    private final TelemetryPersistenceFailureClassifier classifier =
        new TelemetryPersistenceFailureClassifier();

    @Test
    void recognizesDuplicateMessageIdThroughCauseChain() {
        DataIntegrityViolationException failure = failure("uq_telemetry_samples_message_id");

        assertThat(classifier.isDuplicateMessageId(failure)).isTrue();
        assertThat(classifier.isDuplicateAlertSourceType(failure)).isFalse();
    }

    @Test
    void recognizesDuplicateAlertSourceTypeThroughCauseChain() {
        DataIntegrityViolationException failure =
            failure("uq_maintenance_alerts_source_message_type");

        assertThat(classifier.isDuplicateAlertSourceType(failure)).isTrue();
        assertThat(classifier.isDuplicateMessageId(failure)).isFalse();
    }

    @Test
    void doesNotClassifyUnrelatedConstraint() {
        DataIntegrityViolationException failure =
            failure("fk_maintenance_alerts_source_sample");

        assertThat(classifier.isDuplicateMessageId(failure)).isFalse();
        assertThat(classifier.isDuplicateAlertSourceType(failure)).isFalse();
    }

    private static DataIntegrityViolationException failure(String constraintName) {
        SQLException sqlFailure = new SQLException("constraint violation", "23505");
        ConstraintViolationException hibernateFailure = new ConstraintViolationException(
            "constraint violation", sqlFailure, "insert into maintenance_alerts ...",
            constraintName);
        return new DataIntegrityViolationException("persistence failure", hibernateFailure);
    }
}
