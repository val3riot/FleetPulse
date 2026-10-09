package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Import(MaintenanceAlertTransitionIntegrationTest.FixedClockConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class MaintenanceAlertTransitionIntegrationTest {
    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID ALERT_ID = UUID.fromString("f2607610-5100-4723-93d0-e6bbdcf00da0");
    private static final UUID SOURCE_MESSAGE_ID = UUID
            .fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final Instant CREATED_AT = Instant.parse("2026-09-14T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRESQL = new PostgreSQLContainer(
            "postgres:17.10-alpine3.23")
            .withDatabaseName("fleetpulse_alert_transition_test")
            .withUsername("fleetpulse")
            .withPassword("fleetpulse_test");

    @Autowired
    private MaintenanceAlertCommandService commandService;

    @Autowired
    private MaintenanceAlertRepository repository;

    @Autowired
    private MaintenanceAlertStateMachine stateMachine;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

    @BeforeEach
    void insertFixture() {
        jdbc.update("DELETE FROM maintenance_alerts");
        jdbc.update("DELETE FROM telemetry_samples");
        jdbc.update("DELETE FROM vehicles");
        jdbc.update("""
                INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                    next_service_at_km, created_at)
                VALUES (?, 'VAN-FP036', 'FP036AA', 'ACTIVE', 15000, 90000, ?)
                """, VEHICLE_ID, timestamp(CREATED_AT));
        jdbc.update("""
                INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                    received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                    odometer_km, latitude, longitude)
                VALUES (?, ?, 1, ?, ?, ?, 50, 110, 12.5, 85000, 41.9, 12.5)
                """, SOURCE_MESSAGE_ID, VEHICLE_ID, timestamp(CREATED_AT), timestamp(CREATED_AT),
                timestamp(CREATED_AT));
        insertOpenAlert();
    }

    @Test
    void migrationInitializesVersionToZero() {
        Long version = jdbc.queryForObject(
                "SELECT version FROM maintenance_alerts WHERE id = ?", Long.class, ALERT_ID);

        assertThat(version).isZero();
    }

    @Test
    void acknowledgesOnceAndKeepsTimestampOnIdempotentReplay() {
        ChangeAlertStatusRequest request = new ChangeAlertStatusRequest(
                AlertStatusTarget.ACKNOWLEDGED);

        MaintenanceAlertResponse first = commandService.changeStatus(ALERT_ID, request);
        MaintenanceAlertResponse replay = commandService.changeStatus(ALERT_ID, request);

        assertThat(first.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(first.acknowledgedAt()).isEqualTo(NOW);
        assertThat(first.closedAt()).isNull();
        assertThat(replay).isEqualTo(first);
        assertThat(readVersion()).isEqualTo(1L);
    }

    @Test
    void closesOpenAlertWithoutAcknowledgingIt() {
        MaintenanceAlertResponse response = commandService.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.CLOSED));

        assertThat(response.status()).isEqualTo(AlertStatus.CLOSED);
        assertThat(response.acknowledgedAt()).isNull();
        assertThat(response.closedAt()).isEqualTo(NOW);
        assertThat(readVersion()).isEqualTo(1L);
    }

    @Test
    void closesAcknowledgedAlertAndPreservesAcknowledgement() {
        commandService.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.ACKNOWLEDGED));
        MaintenanceAlertResponse response = commandService.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.CLOSED));

        assertThat(response.status()).isEqualTo(AlertStatus.CLOSED);
        assertThat(response.acknowledgedAt()).isEqualTo(NOW);
        assertThat(response.closedAt()).isEqualTo(NOW);
        assertThat(readVersion()).isEqualTo(2L);
    }

    @Test
    void rejectsAcknowledgeAfterClose() {
        commandService.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.CLOSED));

        assertThatThrownBy(() -> commandService.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.ACKNOWLEDGED)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT));
        assertThat(readVersion()).isEqualTo(1L);
    }

    @Test
    void staleSnapshotCannotOverwriteCommittedTransition() {
        MaintenanceAlertEntity firstSnapshot = readDetachedAlert();
        MaintenanceAlertEntity secondSnapshot = readDetachedAlert();
        stateMachine.transition(firstSnapshot, AlertStatusTarget.CLOSED, NOW);
        stateMachine.transition(secondSnapshot, AlertStatusTarget.ACKNOWLEDGED, NOW);

        writeDetachedAlert(firstSnapshot);

        assertThatThrownBy(() -> writeDetachedAlert(secondSnapshot))
                .isInstanceOf(OptimisticLockingFailureException.class);
        MaintenanceAlertEntity persisted = readDetachedAlert();
        assertThat(persisted.getStatus()).isEqualTo(AlertStatus.CLOSED);
        assertThat(persisted.getClosedAt()).isEqualTo(NOW);
        assertThat(persisted.getAcknowledgedAt()).isNull();
        assertThat(persisted.getVersion()).isEqualTo(1L);
    }

    @Test
    void concurrentAcknowledgeAndCloseNeverProduceImpossibleState() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MaintenanceAlertResponse> acknowledge = executor.submit(() -> {
                start.await();
                return commandService.changeStatus(ALERT_ID,
                        new ChangeAlertStatusRequest(AlertStatusTarget.ACKNOWLEDGED));
            });
            Future<MaintenanceAlertResponse> close = executor.submit(() -> {
                start.await();
                return commandService.changeStatus(ALERT_ID,
                        new ChangeAlertStatusRequest(AlertStatusTarget.CLOSED));
            });

            start.countDown();
            MaintenanceAlertResponse closeResponse = close.get(10, TimeUnit.SECONDS);
            assertThat(closeResponse.status()).isEqualTo(AlertStatus.CLOSED);
            assertAcknowledgeOutcomeIsSerialized(acknowledge);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        MaintenanceAlertEntity persisted = readDetachedAlert();
        assertThat(persisted.getStatus()).isEqualTo(AlertStatus.CLOSED);
        assertThat(persisted.getClosedAt()).isEqualTo(NOW);
        assertThat(persisted.getAcknowledgedAt() == null
                || NOW.equals(persisted.getAcknowledgedAt())).isTrue();
        assertThat(persisted.getVersion()).isBetween(1L, 2L);
    }

    private void assertAcknowledgeOutcomeIsSerialized(Future<MaintenanceAlertResponse> future)
            throws Exception {
        try {
            MaintenanceAlertResponse response = future.get(10, TimeUnit.SECONDS);
            assertThat(response.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        } catch (ExecutionException exception) {
            assertThat(exception.getCause()).isInstanceOfSatisfying(ApplicationException.class,
                    applicationException -> assertThat(applicationException.getErrorCode())
                            .isEqualTo(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT));
        }
    }

    private MaintenanceAlertEntity readDetachedAlert() {
        TransactionTemplate transaction = requiresNewTransaction();
        MaintenanceAlertEntity alert = transaction.execute(
                status -> repository.findById(ALERT_ID).orElseThrow());
        assertThat(alert).isNotNull();
        return alert;
    }

    private void writeDetachedAlert(MaintenanceAlertEntity alert) {
        TransactionTemplate transaction = requiresNewTransaction();
        transaction.executeWithoutResult(status -> repository.saveAndFlush(alert));
    }

    private TransactionTemplate requiresNewTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private long readVersion() {
        Long version = jdbc.queryForObject(
                "SELECT version FROM maintenance_alerts WHERE id = ?", Long.class, ALERT_ID);
        assertThat(version).isNotNull();
        return version;
    }

    private void insertOpenAlert() {
        jdbc.update("""
                INSERT INTO maintenance_alerts (id, vehicle_id, source_message_id, type, severity,
                    description, status, created_at, acknowledged_at, closed_at)
                VALUES (?, ?, ?, 'ENGINE_TEMPERATURE_HIGH', 'HIGH',
                    'Temperature above threshold', 'OPEN', ?, NULL, NULL)
                """, ALERT_ID, VEHICLE_ID, SOURCE_MESSAGE_ID, timestamp(CREATED_AT));
    }

    private OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
