package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.processor.telemetry.alert.AlertCandidate;
import it.fleetpulse.processor.telemetry.alert.AlertSeverity;
import it.fleetpulse.processor.telemetry.alert.AlertType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TelemetryAggregateWriter.class, MaintenanceAlertMapper.class})
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TelemetryAggregateWriterIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID MESSAGE_ID =
        UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30.150Z");

    @Autowired
    private TelemetryAggregateWriter writer;

    @Autowired
    private TelemetrySampleRepository sampleRepository;

    @Autowired
    private MaintenanceAlertRepository alertRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void insertVehicle() {
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                next_service_at_km, created_at)
            VALUES (?, 'VAN-AGGREGATE', 'FP034AA', 'ACTIVE', 15000, 90000, ?)
            """, VEHICLE_ID, OffsetDateTime.ofInstant(CREATED_AT, ZoneOffset.UTC));
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM maintenance_alerts WHERE vehicle_id = ?", VEHICLE_ID);
        jdbc.update("DELETE FROM telemetry_samples WHERE vehicle_id = ?", VEHICLE_ID);
        jdbc.update("DELETE FROM vehicles WHERE id = ?", VEHICLE_ID);
    }

    @Test
    void persistsSampleWithoutAlerts() {
        TelemetryAggregateWriteResult result = writer.insert(sample(), List.of(), CREATED_AT);

        assertThat(result.sample().getId()).isNotNull();
        assertThat(result.alerts()).isEmpty();
        assertThat(sampleRepository.count()).isEqualTo(1);
        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void persistsSampleAndAlertInSameAggregate() {
        AlertCandidate candidate =
            candidate(AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH);

        TelemetryAggregateWriteResult result =
            writer.insert(sample(), List.of(candidate), CREATED_AT);

        assertThat(result.alerts()).singleElement().satisfies(alert -> {
            assertThat(alert.getId()).isNotNull();
            assertThat(alert.getVehicleId()).isEqualTo(VEHICLE_ID);
            assertThat(alert.getSourceMessageId()).isEqualTo(MESSAGE_ID);
            assertThat(alert.getType()).isEqualTo(AlertType.ENGINE_TEMPERATURE_HIGH);
            assertThat(alert.getSeverity()).isEqualTo(AlertSeverity.HIGH);
            assertThat(alert.getStatus()).isEqualTo(AlertStatus.OPEN);
            assertThat(alert.getCreatedAt()).isEqualTo(CREATED_AT);
        });
        assertThat(sampleRepository.count()).isEqualTo(1);
        assertThat(alertRepository.count()).isEqualTo(1);
    }

    @Test
    void persistsMultipleAlertsWithDistinctTypes() {
        List<AlertCandidate> candidates = List.of(
            candidate(AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH),
            candidate(AlertType.BATTERY_VOLTAGE_LOW, AlertSeverity.HIGH),
            candidate(AlertType.SERVICE_DUE, AlertSeverity.MEDIUM));

        TelemetryAggregateWriteResult result = writer.insert(sample(), candidates, CREATED_AT);

        assertThat(result.alerts()).extracting(MaintenanceAlertEntity::getType)
            .containsExactlyInAnyOrder(AlertType.ENGINE_TEMPERATURE_HIGH,
                AlertType.BATTERY_VOLTAGE_LOW, AlertType.SERVICE_DUE);
        assertThat(alertRepository.count()).isEqualTo(3);
    }

    @Test
    void rollsBackSampleWhenAlertViolatesCompositeForeignKey() {
        UUID otherVehicleId = UUID.randomUUID();
        AlertCandidate invalidCandidate = new AlertCandidate(otherVehicleId, MESSAGE_ID,
            AlertType.SERVICE_DUE, AlertSeverity.MEDIUM, "Invalid vehicle association");

        assertThatThrownBy(() -> writer.insert(sample(), List.of(invalidCandidate), CREATED_AT))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(sampleRepository.count()).isZero();
        assertThat(alertRepository.count()).isZero();
    }

    @Test
    void databaseRejectsDuplicateSourceAndType() {
        AlertCandidate candidate = candidate(AlertType.SERVICE_DUE, AlertSeverity.MEDIUM);
        writer.insert(sample(), List.of(candidate), CREATED_AT);
        MaintenanceAlertEntity duplicate =
            new MaintenanceAlertMapper().toEntity(candidate, CREATED_AT);

        assertThatThrownBy(() -> alertRepository.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("uq_maintenance_alerts_source_message_type");
        assertThat(alertRepository.count()).isEqualTo(1);
    }

    private static TelemetrySampleEntity sample() {
        return new TelemetrySampleEntity(MESSAGE_ID, VEHICLE_ID, 42,
            Instant.parse("2026-08-01T10:15:30Z"),
            Instant.parse("2026-08-01T10:15:30.083Z"), CREATED_AT,
            72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964);
    }

    private static AlertCandidate candidate(AlertType type, AlertSeverity severity) {
        return new AlertCandidate(VEHICLE_ID, MESSAGE_ID, type, severity,
            "Synthetic " + type + " alert");
    }
}
