package it.fleetpulse.api.alert;

import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(MaintenanceAlertPageableFactory.class)
@ActiveProfiles("test")
class MaintenanceAlertRepositoryIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final UUID VEHICLE_ONE =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID VEHICLE_TWO =
        UUID.fromString("189c8a21-6dea-4562-950c-5a22818cf7f2");
    private static final UUID ALERT_ONE =
        UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ALERT_TWO =
        UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ALERT_THREE =
        UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ALERT_FOUR =
        UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ALERT_FIVE =
        UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final Instant AT_TEN = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant AT_TEN_THIRTY = Instant.parse("2026-08-01T10:30:00Z");
    private static final Instant AT_ELEVEN = Instant.parse("2026-08-01T11:00:00Z");

    @Autowired
    private MaintenanceAlertRepository repository;

    @Autowired
    private MaintenanceAlertPageableFactory pageableFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void insertFixture() {
        insertVehicle(VEHICLE_ONE, "VAN-ALERT-1", "FP035AA");
        insertVehicle(VEHICLE_TWO, "VAN-ALERT-2", "FP035BB");
        insertSample(1, VEHICLE_ONE);
        insertSample(2, VEHICLE_ONE);
        insertSample(3, VEHICLE_ONE);
        insertSample(4, VEHICLE_TWO);
        insertSample(5, VEHICLE_ONE);
        insertAlert(ALERT_ONE, 1, VEHICLE_ONE, "ENGINE_TEMPERATURE_HIGH", "HIGH", "OPEN",
            AT_TEN, null, null);
        insertAlert(ALERT_TWO, 2, VEHICLE_ONE, "BATTERY_VOLTAGE_LOW", "MEDIUM",
            "ACKNOWLEDGED", AT_TEN_THIRTY, AT_TEN_THIRTY.plusSeconds(60), null);
        insertAlert(ALERT_THREE, 3, VEHICLE_ONE, "SERVICE_DUE", "LOW", "CLOSED",
            AT_ELEVEN, null, AT_ELEVEN.plusSeconds(60));
        insertAlert(ALERT_FOUR, 4, VEHICLE_TWO, "SERVICE_DUE", "CRITICAL", "OPEN",
            AT_TEN_THIRTY, null, null);
        insertAlert(ALERT_FIVE, 5, VEHICLE_ONE, "SERVICE_DUE", "CRITICAL", "OPEN",
            AT_TEN_THIRTY, null, null);
    }

    @Test
    void appliesEveryOptionalFilterIndividually() {
        assertThat(search(criteria(null, AlertStatus.ACKNOWLEDGED, null, null, null, null)))
            .containsExactly(ALERT_TWO);
        assertThat(search(criteria(null, null, AlertType.BATTERY_VOLTAGE_LOW, null, null, null)))
            .containsExactly(ALERT_TWO);
        assertThat(search(criteria(null, null, null, AlertSeverity.LOW, null, null)))
            .containsExactly(ALERT_THREE);
        assertThat(search(criteria(VEHICLE_TWO, null, null, null, null, null)))
            .containsExactly(ALERT_FOUR);
    }

    @Test
    void combinesFiltersWithAndSemantics() {
        MaintenanceAlertSearchCriteria criteria = criteria(VEHICLE_ONE, AlertStatus.OPEN,
            AlertType.SERVICE_DUE, AlertSeverity.CRITICAL, null, null);

        assertThat(search(criteria)).containsExactly(ALERT_FIVE);
    }

    @Test
    void treatsTimeRangeBoundariesAsInclusive() {
        MaintenanceAlertSearchCriteria criteria =
            criteria(null, null, null, null, AT_TEN_THIRTY, AT_TEN_THIRTY);

        assertThat(search(criteria)).containsExactly(ALERT_FIVE, ALERT_FOUR, ALERT_TWO);
    }

    @Test
    void globalAndScopedCollectionsReturnCoherentSets() {
        List<UUID> global = search(criteria(null, null, null, null, null, null));
        List<UUID> scoped = search(criteria(VEHICLE_ONE, null, null, null, null, null));

        assertThat(global).containsExactly(ALERT_THREE, ALERT_FIVE, ALERT_FOUR, ALERT_TWO,
            ALERT_ONE);
        assertThat(scoped).containsExactly(ALERT_THREE, ALERT_FIVE, ALERT_TWO, ALERT_ONE);
    }

    @Test
    void paginatesDeterministicallyWhenCreatedAtIsEqual() {
        MaintenanceAlertSearchCriteria criteria =
            criteria(null, null, null, null, AT_TEN_THIRTY, AT_TEN_THIRTY);
        Pageable firstPage = pageableFactory.create(0, 1, "createdAt,desc");
        Pageable secondPage = pageableFactory.create(1, 1, "createdAt,desc");

        Page<MaintenanceAlertEntity> first =
            repository.findAll(MaintenanceAlertSpecifications.from(criteria), firstPage);
        Page<MaintenanceAlertEntity> second =
            repository.findAll(MaintenanceAlertSpecifications.from(criteria), secondPage);

        assertThat(first.getContent()).extracting(MaintenanceAlertEntity::getId)
            .containsExactly(ALERT_FIVE);
        assertThat(second.getContent()).extracting(MaintenanceAlertEntity::getId)
            .containsExactly(ALERT_FOUR);
    }

    private List<UUID> search(MaintenanceAlertSearchCriteria criteria) {
        Pageable pageable = pageableFactory.create(0, 50, "createdAt,desc");
        return repository.findAll(MaintenanceAlertSpecifications.from(criteria), pageable)
            .getContent().stream().map(MaintenanceAlertEntity::getId).toList();
    }

    private MaintenanceAlertSearchCriteria criteria(UUID vehicleId, AlertStatus status,
        AlertType type, AlertSeverity severity, Instant from, Instant to) {
        return new MaintenanceAlertSearchCriteria(vehicleId, status, type, severity, from, to);
    }

    private void insertVehicle(UUID id, String externalCode, String plate) {
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                next_service_at_km, created_at)
            VALUES (?, ?, ?, 'ACTIVE', 15000, 90000, ?)
            """, id, externalCode, plate, timestamp(AT_TEN));
    }

    private void insertSample(int suffix, UUID vehicleId) {
        UUID messageId = sourceMessageId(suffix);
        jdbc.update("""
            INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                odometer_km, latitude, longitude)
            VALUES (?, ?, ?, ?, ?, ?, 50, 90, 12.5, 85000, 41.9, 12.5)
            """, messageId, vehicleId, suffix, timestamp(AT_TEN), timestamp(AT_TEN),
            timestamp(AT_TEN));
    }

    private void insertAlert(UUID id, int messageSuffix, UUID vehicleId, String type,
        String severity, String status, Instant createdAt, Instant acknowledgedAt,
        Instant closedAt) {
        jdbc.update("""
            INSERT INTO maintenance_alerts (id, vehicle_id, source_message_id, type, severity,
                description, status, created_at, acknowledged_at, closed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, vehicleId, sourceMessageId(messageSuffix), type, severity,
            "Synthetic " + type, status, timestamp(createdAt), timestamp(acknowledgedAt),
            timestamp(closedAt));
    }

    private UUID sourceMessageId(int suffix) {
        return UUID.fromString("00000000-0000-0000-0001-" + String.format("%012d", suffix));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
