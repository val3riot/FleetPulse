package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaintenanceAlertPageableFactoryTest {
    private final MaintenanceAlertPageableFactory factory =
        new MaintenanceAlertPageableFactory();

    @Test
    void createsDefaultDeterministicOrdering() {
        Pageable pageable = factory.create(0, 50, "createdAt,desc");

        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void appliesAscendingDirectionToTieBreaker() {
        Pageable pageable = factory.create(2, 10, "createdAt,asc");

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort().getOrderFor("createdAt").getDirection())
            .isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getSort().getOrderFor("id").getDirection())
            .isEqualTo(Sort.Direction.ASC);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"-1|50|createdAt,desc", "0|0|createdAt,desc",
        "0|101|createdAt,desc", "0|50|status,desc", "0|50|createdAt,up",
        "0|50|createdAt"})
    void rejectsInvalidPaginationAndSort(int page, int size, String sort) {
        assertThatThrownBy(() -> factory.create(page, size, sort))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.REQUEST_INVALID));
    }
}
