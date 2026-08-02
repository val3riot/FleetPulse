package it.fleetpulse.api.common;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseConstraintErrorResolverTest {

    private final DatabaseConstraintErrorResolver resolver = new DatabaseConstraintErrorResolver();

    /**
     * Verifica la conversione del constraint univoco sul codice esterno.
     */
    @Test
    @DisplayName("Converte il constraint del codice esterno")
    void resolvesExternalCodeConstraint() {
        assertThat(resolver.resolve(exception("uq_vehicles_external_code")))
                .contains(ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT);
    }

    /**
     * Verifica la conversione del constraint univoco sulla targa.
     */
    @Test
    @DisplayName("Converte il constraint della targa")
    void resolvesPlateConstraint() {
        assertThat(resolver.resolve(exception("uq_vehicles_plate")))
                .contains(ErrorCode.VEHICLE_PLATE_CONFLICT);
    }

    /**
     * Verifica che un constraint sconosciuto non venga classificato come conflitto noto.
     */
    @Test
    @DisplayName("Ignora un constraint sconosciuto")
    void ignoresUnknownConstraint() {
        assertThat(resolver.resolve(exception("uq_other"))).isEmpty();
    }

    /**
     * Verifica che il resolver attraversi tutti i livelli della catena delle cause.
     */
    @Test
    @DisplayName("Attraversa la catena delle cause")
    void traversesCauseChain() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                "wrapper",
                new IllegalStateException(exception("uq_vehicles_plate"))
        );

        assertThat(resolver.resolve(exception)).contains(ErrorCode.VEHICLE_PLATE_CONFLICT);
    }

    /**
     * Verifica il comportamento quando Hibernate non espone il nome del constraint.
     */
    @Test
    @DisplayName("Restituisce empty senza nome del constraint")
    void returnsEmptyWithoutConstraintName() {
        assertThat(resolver.resolve(exception(null))).isEmpty();
    }

    /**
     * Costruisce una violazione Hibernate realistica senza analizzare messaggi SQL.
     */
    private DataIntegrityViolationException exception(String constraintName) {
        SQLException sqlException = new SQLException("duplicate", "23505");
        ConstraintViolationException hibernateException = new ConstraintViolationException(
                "constraint violation",
                sqlException,
                "insert into vehicles ...",
                constraintName
        );
        return new DataIntegrityViolationException("integrity violation", hibernateException);
    }
}
