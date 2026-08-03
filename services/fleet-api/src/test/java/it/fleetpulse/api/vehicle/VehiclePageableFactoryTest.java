package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VehiclePageableFactoryTest {

    private final VehiclePageableFactory factory = new VehiclePageableFactory();

    /**
     * Verifica pagina, dimensione, sort richiesto e tie-breaker stabile.
     */
    @Test
    @DisplayName("Crea un pageable valido con ordinamento deterministico")
    void createsValidPageable() {
        Pageable pageable = factory.create(2, 50, "plate,asc");

        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getSort()).isEqualTo(
                Sort.by(Sort.Direction.ASC, "plate")
                        .and(Sort.by(Sort.Direction.ASC, "id"))
        );
    }

    /**
     * Verifica l'ordinamento predefinito quando il parametro è assente o vuoto.
     */
    @Test
    @DisplayName("Usa createdAt discendente come sort predefinito")
    void usesDefaultSort() {
        Pageable pageable = factory.create(0, 20, null);

        assertThat(pageable.getSort()).isEqualTo(
                Sort.by(Sort.Direction.DESC, "createdAt")
                        .and(Sort.by(Sort.Direction.ASC, "id"))
        );
    }

    /**
     * Verifica tutti i campi di ordinamento pubblicati dal contratto API.
     */
    @ParameterizedTest
    @ValueSource(strings = {"createdAt", "externalCode", "plate", "status"})
    @DisplayName("Accetta tutti i campi sort documentati")
    void acceptsDocumentedSortFields(String field) {
        Pageable pageable = factory.create(0, 20, field + ",desc");

        assertThat(pageable.getSort().getOrderFor(field))
                .isEqualTo(Sort.Order.desc(field));
    }

    /**
     * Verifica che pagina e dimensione fuori limite siano rifiutate.
     */
    @ParameterizedTest
    @ValueSource(strings = {"-1,20", "0,0", "0,101"})
    @DisplayName("Rifiuta pagina e dimensione fuori limite")
    void rejectsInvalidPagination(String values) {
        String[] parts = values.split(",");

        assertInvalid(() -> factory.create(
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                "createdAt,desc"
        ));
    }

    /**
     * Verifica che formato, campo e direzione sort non supportati siano rifiutati.
     */
    @ParameterizedTest
    @ValueSource(strings = {"plate", "plate,", "plate,asc,extra", "odometer,asc", "plate,sideways"})
    @DisplayName("Rifiuta espressioni sort non supportate")
    void rejectsInvalidSort(String sort) {
        assertInvalid(() -> factory.create(0, 20, sort));
    }

    /**
     * Verifica il codice applicativo prodotto da una validazione fallita.
     */
    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.REQUEST_INVALID));
    }
}
