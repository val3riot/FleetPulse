package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.DatabaseConstraintErrorResolver;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@ActiveProfiles("test")
@Import(DatabaseConstraintErrorResolver.class)
class VehiclePersistenceIntegrationTest extends PostgreSqlIntegrationSupport {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T08:00:00Z");

    @Autowired
    private VehicleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseConstraintErrorResolver resolver;

    /**
     * Verifica che Flyway abbia applicato con successo la migration V1 originale.
     */
    @Test
    @DisplayName("Flyway applica la migration V1 su un database vuoto")
    void appliesFlywayMigration() {
        Boolean success = jdbcTemplate.queryForObject(
                "select success from flyway_schema_history where version = '1'",
                Boolean.class
        );

        assertThat(success).isTrue();
    }

    /**
     * Verifica persistenza, UUID, enum, timestamp e lettura reale dell'entity.
     */
    @Test
    @DisplayName("Persiste e rilegge un veicolo sullo schema validato da Hibernate")
    void persistsAndReadsVehicle() {
        VehicleEntity saved = repository.saveAndFlush(entity("VAN-PERSIST", "FP100AA"));

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findById(saved.getId()))
                .get()
                .satisfies(vehicle -> {
                    assertThat(vehicle.getExternalCode()).isEqualTo("VAN-PERSIST");
                    assertThat(vehicle.getPlate()).isEqualTo("FP100AA");
                    assertThat(vehicle.getStatus()).isEqualTo(VehicleStatus.ACTIVE);
                    assertThat(vehicle.getCreatedAt()).isEqualTo(CREATED_AT);
                });
        assertThat(jdbcTemplate.queryForObject(
                "select status from vehicles where id = ?",
                String.class,
                saved.getId()
        )).isEqualTo("ACTIVE");
    }

    /**
     * Verifica il constraint PostgreSQL sul codice esterno e la sua classificazione.
     */
    @Test
    @DisplayName("Il constraint reale sul codice esterno produce il codice 409 previsto")
    void enforcesExternalCodeConstraint() {
        repository.saveAndFlush(entity("VAN-DUP", "FP101AA"));

        assertThatThrownBy(() -> repository.saveAndFlush(entity("VAN-DUP", "FP102AA")))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, exception ->
                        assertThat(resolver.resolve(exception))
                                .contains(ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT));
    }

    /**
     * Verifica il constraint PostgreSQL sulla targa e la sua classificazione.
     */
    @Test
    @DisplayName("Il constraint reale sulla targa produce il codice 409 previsto")
    void enforcesPlateConstraint() {
        repository.saveAndFlush(entity("VAN-PLATE-1", "FP103AA"));

        assertThatThrownBy(() -> repository.saveAndFlush(entity("VAN-PLATE-2", "FP103AA")))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, exception ->
                        assertThat(resolver.resolve(exception))
                                .contains(ErrorCode.VEHICLE_PLATE_CONFLICT));
    }

    /**
     * Costruisce un veicolo valido con dati univoci per il test di persistenza.
     */
    private VehicleEntity entity(String externalCode, String plate) {
        return new VehicleEntity(externalCode, plate, VehicleStatus.ACTIVE, 15_000, 90_000L, CREATED_AT);
    }
}
