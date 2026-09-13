package it.fleetpulse.processor.telemetry.alert;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class AlertEnumMigrationIntegrationTest {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID MESSAGE_ID =
        UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final Instant NOW = Instant.parse("2026-08-01T10:16:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
        new PostgreSQLContainer("postgres:17.10-alpine3.23")
            .withDatabaseName("fleetpulse_alert_migration_test")
            .withUsername("fleetpulse")
            .withPassword("fleetpulse_test");

    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(POSTGRESQL.getJdbcUrl(),
            POSTGRESQL.getUsername(), POSTGRESQL.getPassword()));
        flyway().clean();
    }

    @Test
    void appliesV3OnFreshDatabaseAndEnforcesBothEnums() {
        flyway().migrate();
        seedSourceSample();

        assertThat(jdbc.queryForObject(
            "SELECT success FROM flyway_schema_history WHERE version = '3'", Boolean.class))
            .isTrue();
        insertAlert("ENGINE_TEMPERATURE_HIGH", "HIGH");

        assertThatThrownBy(() -> insertAlert("UNKNOWN_TYPE", "HIGH"))
            .hasMessageContaining("ck_maintenance_alerts_type");
        assertThatThrownBy(() -> insertAlert("SERVICE_DUE", "URGENT"))
            .hasMessageContaining("ck_maintenance_alerts_severity");
    }

    @Test
    void upgradesSchemaAndExistingSupportedDataFromV1() {
        flyway(MigrationVersion.fromVersion("1")).migrate();
        seedSourceSample();
        insertAlert("BATTERY_VOLTAGE_LOW", "HIGH");

        flyway().migrate();

        assertThat(jdbc.queryForObject(
            "SELECT success FROM flyway_schema_history WHERE version = '3'", Boolean.class))
            .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM maintenance_alerts", Long.class))
            .isEqualTo(1L);
    }

    private Flyway flyway() {
        return Flyway.configure()
            .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
    }

    private Flyway flyway(MigrationVersion target) {
        return Flyway.configure()
            .dataSource(POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(),
                POSTGRESQL.getPassword())
            .locations("classpath:db/migration")
            .target(target)
            .cleanDisabled(false)
            .load();
    }

    private void seedSourceSample() {
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                next_service_at_km, created_at)
            VALUES (?, 'VAN-MIGRATION', 'FP033AA', 'ACTIVE', 15000, 90000, ?)
            """, VEHICLE_ID, Timestamp.from(NOW));
        jdbc.update("""
            INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                odometer_km, latitude, longitude)
            VALUES (?, ?, 42, ?, ?, ?, 72.4, 91.8, 12.6, 90000, 41.9028, 12.4964)
            """, MESSAGE_ID, VEHICLE_ID, Timestamp.from(NOW), Timestamp.from(NOW),
            Timestamp.from(NOW));
    }

    private void insertAlert(String type, String severity) {
        jdbc.update("""
            INSERT INTO maintenance_alerts (id, vehicle_id, source_message_id, type, severity,
                description, status, created_at)
            VALUES (?, ?, ?, ?, ?, 'Synthetic migration test alert', 'OPEN', ?)
            """, UUID.randomUUID(), VEHICLE_ID, MESSAGE_ID, type, severity, Timestamp.from(NOW));
    }
}
