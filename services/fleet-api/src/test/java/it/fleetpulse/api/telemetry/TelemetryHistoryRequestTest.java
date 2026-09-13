package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryHistoryRequestTest {
    private static final Instant FROM = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T11:00:00Z");
    private static final ValidatorFactory VALIDATOR_FACTORY =
        Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private final TelemetryHistoryRequestValidator rangeValidator =
        new TelemetryHistoryRequestValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void appliesDocumentedPaginationAndSortDefaults() {
        TelemetryHistoryRequest request = new TelemetryHistoryRequest(FROM, TO, null, null, null);

        assertThat(request.page()).isZero();
        assertThat(request.size()).isEqualTo(50);
        assertThat(request.sort()).isEqualTo("observedAt,desc");
        assertThat(VALIDATOR.validate(request)).isEmpty();
    }

    @Test
    void rejectsMissingRangeAndInvalidPaginationOrSort() {
        TelemetryHistoryRequest request =
            new TelemetryHistoryRequest(null, null, -1, 101, "sequenceNumber,desc");

        assertThat(VALIDATOR.validate(request)).extracting(violation -> violation.getPropertyPath()
            .toString()).contains("from", "to", "page", "size", "sort");
    }

    @Test
    void acceptsAnInclusiveSingleInstantRange() {
        TelemetryHistoryRequest request =
            new TelemetryHistoryRequest(FROM, FROM, 0, 1, "observedAt,asc");

        assertThatCode(() -> rangeValidator.validate(request)).doesNotThrowAnyException();
    }

    @Test
    void mapsAnInvertedRangeToTheDedicatedError() {
        TelemetryHistoryRequest request =
            new TelemetryHistoryRequest(TO, FROM, 0, 50, "observedAt,desc");

        assertThatThrownBy(() -> rangeValidator.validate(request))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.REQUEST_INVALID_TIME_RANGE));
    }
}
