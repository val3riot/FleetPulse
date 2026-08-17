package it.fleetpulse.api.common;

import org.hibernate.TransactionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseAvailabilityClassifierTest {

    private final DatabaseAvailabilityClassifier classifier = new DatabaseAvailabilityClassifier();

    /**
     * Verifica che gli SQLState della classe 08 siano errori di connessione.
     */
    @Test
    @DisplayName("Riconosce gli SQLState della classe 08")
    void recognizesConnectionSqlState() {
        assertThat(
            classifier.isConnectionFailure(new SQLException("connection", "08006"))).isTrue();
    }

    /**
     * Verifica che uno SQLState non relativo alla connessione venga ignorato.
     */
    @Test
    @DisplayName("Ignora gli SQLState non di connessione")
    void ignoresDifferentSqlState() {
        assertThat(
            classifier.isConnectionFailure(new SQLException("constraint", "23505"))).isFalse();
    }

    /**
     * Verifica che uno SQLState assente non venga classificato come connection failure.
     */
    @Test
    @DisplayName("Ignora uno SQLState nullo")
    void ignoresNullSqlState() {
        assertThat(
            classifier.isConnectionFailure(new SQLException("unknown", (String) null))).isFalse();
    }

    /**
     * Verifica che la ricerca raggiunga una SQLException annidata.
     */
    @Test
    @DisplayName("Attraversa più livelli della catena delle cause")
    void traversesCauseChain() {
        RuntimeException exception = new RuntimeException(
            new IllegalStateException(new SQLException("connection", "08001")));

        assertThat(classifier.isConnectionFailure(exception)).isTrue();
    }

    /**
     * Verifica che una failure conservata come suppressed venga riconosciuta.
     */
    @Test
    @DisplayName("Attraversa le eccezioni suppressed")
    void traversesSuppressedExceptions() {
        RuntimeException exception = new RuntimeException("rollback failed");
        exception.addSuppressed(new SQLException("original connection failure", "08006"));

        assertThat(classifier.isConnectionFailure(exception)).isTrue();
    }

    /**
     * Verifica che la catena JDBC nextException venga attraversata.
     */
    @Test
    @DisplayName("Attraversa la catena JDBC nextException")
    void traversesNextSqlException() {
        SQLException exception = new SQLException("wrapper", (String) null);
        exception.setNextException(new SQLException("connection", "08001"));

        assertThat(classifier.isConnectionFailure(exception)).isTrue();
    }

    /**
     * Verifica la struttura prodotta da Hibernate quando fallisce anche il rollback.
     */
    @Test
    @DisplayName("Riconosce il fallimento Hibernate con connessione JDBC chiusa")
    void recognizesHibernateRollbackConnectionFailure() {
        TransactionException exception = new TransactionException("rollback failure",
            new SQLException("connection closed", (String) null));

        assertThat(classifier.isConnectionFailure(exception)).isTrue();
    }

    /**
     * Verifica che una failure Hibernate con SQLState noto non venga sovraclassificata.
     */
    @Test
    @DisplayName("Non confonde una transazione Hibernate con un errore di connessione")
    void ignoresHibernateFailureWithNonConnectionSqlState() {
        TransactionException exception = new TransactionException("transaction failure",
            new SQLException("constraint", "23505"));

        assertThat(classifier.isConnectionFailure(exception)).isFalse();
    }

    /**
     * Verifica che un'eccezione priva di SQLException non sia una connection failure.
     */
    @Test
    @DisplayName("Ignora catene prive di SQLException")
    void ignoresChainWithoutSqlException() {
        assertThat(classifier.isConnectionFailure(
            new RuntimeException(new IllegalStateException()))).isFalse();
    }
}
