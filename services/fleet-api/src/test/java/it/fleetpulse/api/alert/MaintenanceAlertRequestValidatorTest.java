package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceAlertRequestValidatorTest {
    private final MaintenanceAlertRequestValidator validator =
        new MaintenanceAlertRequestValidator();

    @Test
    void acceptsMissingAndInclusiveRanges() {
        Instant boundary = Instant.parse("2026-08-01T10:00:00Z");

        assertThatCode(() -> validator.validate(request(null, null))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(request(boundary, boundary)))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsInvertedRangeWithDedicatedCode() {
        Instant from = Instant.parse("2026-08-01T11:00:00Z");
        Instant to = Instant.parse("2026-08-01T10:00:00Z");

        assertThatThrownBy(() -> validator.validate(request(from, to)))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.REQUEST_INVALID_TIME_RANGE));
    }

    private MaintenanceAlertSearchRequest request(Instant from, Instant to) {
        return new MaintenanceAlertSearchRequest(null, null, null, from, to, 0, 50,
            "createdAt,desc");
    }
}
