package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryHistoryPageableFactoryTest {
    private final TelemetryHistoryPageableFactory factory =
        new TelemetryHistoryPageableFactory();

    @Test
    void createsDescendingDefaultWithStableTieBreaker() {
        Pageable pageable = factory.create(0, 50, null);

        assertThat(pageable.getPageNumber()).isZero();
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getSort().getOrderFor("observedAt").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void appliesTheSameDirectionToObservedAtAndId() {
        Pageable pageable = factory.create(2, 25, "observedAt,asc");

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(25);
        assertThat(pageable.getSort().getOrderFor("observedAt").getDirection())
            .isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection())
            .isEqualTo(Sort.Direction.ASC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"createdAt,desc", "observedAt", "observedAt,up",
        "observedAt,desc,id,desc", ",desc", " "})
    void rejectsUnsupportedSortExpressions(String sort) {
        assertInvalid(() -> factory.create(0, 50, sort));
    }

    @Test
    void rejectsUnboundedPagination() {
        assertInvalid(() -> factory.create(-1, 50, "observedAt,desc"));
        assertInvalid(() -> factory.create(0, 0, "observedAt,desc"));
        assertInvalid(() -> factory.create(0, 101, "observedAt,desc"));
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
        assertThatThrownBy(invocation).isInstanceOfSatisfying(ApplicationException.class,
            exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REQUEST_INVALID));
    }
}
