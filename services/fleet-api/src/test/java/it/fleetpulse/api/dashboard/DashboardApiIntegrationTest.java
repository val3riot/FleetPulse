package it.fleetpulse.api.dashboard;

import it.fleetpulse.api.alert.AlertSeverity;
import it.fleetpulse.api.alert.AlertStatus;
import it.fleetpulse.api.alert.MaintenanceAlertRepository;
import it.fleetpulse.api.alert.MaintenanceAlertSummary;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleRepository;
import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import it.fleetpulse.api.vehicle.VehicleStatus;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "fleetpulse.api.dashboard.relevant-alerts-limit=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(DashboardApiIntegrationTest.Configuration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class DashboardApiIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant CUTOFF = NOW.minusSeconds(60);
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DashboardService service;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private MaintenanceAlertRepository alerts;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private TelemetrySampleRepository samples;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM maintenance_alerts");
        jdbc.update("DELETE FROM telemetry_samples");
        jdbc.update("DELETE FROM vehicles");
        SelectCounter.COUNT.set(0);
        SelectCounter.BEFORE_THIRD_SELECT.set(null);
    }

    @Test
    void returnsCompleteEmptyContractWithFourSelects() throws Exception {
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.totalVehicles").value(0))
                .andExpect(jsonPath("$.vehiclesByStatus.ACTIVE").value(0))
                .andExpect(jsonPath("$.vehiclesByStatus.DISABLED").value(0))
                .andExpect(jsonPath("$.recentlyReportingVehicles").value(0))
                .andExpect(jsonPath("$.openAlerts").value(0))
                .andExpect(jsonPath("$.relevantAlerts").isEmpty());
        assertThat(SelectCounter.COUNT.get()).isEqualTo(4);
    }

    @Test
    void countsDistinctVehiclesWithInclusiveObservationBoundariesIncludingDisabled() {
        UUID atCutoff = vehicle("ACTIVE");
        sample(atCutoff, CUTOFF);
        sample(atCutoff, NOW.minusSeconds(20));
        sample(vehicle("DISABLED"), NOW);
        sample(vehicle("ACTIVE"), CUTOFF.minusNanos(1000));
        sample(vehicle("ACTIVE"), NOW.plusNanos(1000));
        vehicle("ACTIVE");

        DashboardResponse response = service.getDashboard();

        assertThat(response.totalVehicles()).isEqualTo(5);
        assertThat(response.vehiclesByStatus()).containsEntry(VehicleStatus.ACTIVE, 4L)
                .containsEntry(VehicleStatus.DISABLED, 1L);
        assertThat(response.recentlyReportingVehicles()).isEqualTo(2);
    }

    @Test
    void ranksAllSeveritiesExcludesClosedAndUsesStableIdAfterCreationTime() {
        UUID vehicleId = vehicle("ACTIVE");
        UUID first = alert(vehicleId, 1, "CRITICAL", "ACKNOWLEDGED", NOW.minusSeconds(10));
        UUID second = alert(vehicleId, 2, "CRITICAL", "OPEN", NOW.minusSeconds(10));
        UUID third = alert(vehicleId, 3, "HIGH", "OPEN", NOW);
        alert(vehicleId, 4, "MEDIUM", "OPEN", NOW);
        alert(vehicleId, 5, "LOW", "OPEN", NOW);
        alert(vehicleId, 6, "CRITICAL", "CLOSED", NOW);

        assertThat(alerts.findRelevantAlerts(
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED), PageRequest.of(0, 100)))
                .extracting(MaintenanceAlertSummary::severity)
                .containsExactly(AlertSeverity.CRITICAL, AlertSeverity.CRITICAL, AlertSeverity.HIGH,
                        AlertSeverity.MEDIUM, AlertSeverity.LOW);
        DashboardResponse response = service.getDashboard();
        assertThat(response.openAlerts()).isEqualTo(4);
        assertThat(response.relevantAlerts()).extracting(DashboardAlertResponse::id)
                .containsExactly(first, second, third);
        assertThat(response.relevantAlerts().getFirst().status())
                .isEqualTo(AlertStatus.ACKNOWLEDGED);
    }

    @Test
    void breaksSeverityTiesByNewestCreationBeforeId() {
        UUID vehicleId = vehicle("ACTIVE");
        UUID older = alert(vehicleId, 1, "HIGH", "OPEN", NOW.minusSeconds(1));
        UUID newer = alert(vehicleId, 2, "HIGH", "OPEN", NOW);
        assertThat(service.getDashboard().relevantAlerts()).extracting(DashboardAlertResponse::id)
                .containsExactly(newer, older);
    }

    @Test
    void exposesOnlySevenSummaryFieldsInRelevantAlerts() throws Exception {
        UUID vehicleId = vehicle("ACTIVE");
        UUID alertId = alert(vehicleId, 1, "HIGH", "OPEN", NOW);
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isOk())
                .andExpect(jsonPath("$.relevantAlerts[0].length()").value(7))
                .andExpect(jsonPath("$.relevantAlerts[0].id").value(alertId.toString()))
                .andExpect(jsonPath("$.relevantAlerts[0].vehicleId").value(vehicleId.toString()))
                .andExpect(jsonPath("$.relevantAlerts[0].type").value("SERVICE_DUE"))
                .andExpect(jsonPath("$.relevantAlerts[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.relevantAlerts[0].status").value("OPEN"))
                .andExpect(jsonPath("$.relevantAlerts[0].description").value("Service due"))
                .andExpect(jsonPath("$.relevantAlerts[0].createdAt").value(NOW.toString()));
    }

    @Test
    void queryCountStaysFourWhenFleetAndAlertVolumeGrow() {
        UUID vehicleId = vehicle("ACTIVE");
        alert(vehicleId, 1, "HIGH", "OPEN", NOW);
        SelectCounter.COUNT.set(0);
        service.getDashboard();
        assertThat(SelectCounter.COUNT.get()).isEqualTo(4);
        for (int index = 2; index <= 101; index++) {
            alert(vehicle("DISABLED"), index, "LOW", "OPEN", NOW);
        }
        SelectCounter.COUNT.set(0);
        DashboardResponse response = service.getDashboard();
        assertThat(SelectCounter.COUNT.get()).isEqualTo(4);
        assertThat(response.totalVehicles()).isEqualTo(101);
        assertThat(response.openAlerts()).isEqualTo(101);
        assertThat(response.relevantAlerts()).hasSize(3);
    }

    @Test
    void projectionsDoNotLoadManagedEntities() {
        UUID vehicleId = vehicle("ACTIVE");
        alert(vehicleId, 1, "HIGH", "OPEN", NOW);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        try {
            statistics.setStatisticsEnabled(true);
            statistics.clear();
            assertThat(service.getDashboard().relevantAlerts()).hasSize(1);
            assertThat(statistics.getEntityLoadCount()).isZero();
            assertThat(statistics.getQueryExecutionCount()).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    @Test
    void allFourQueriesShareSnapshotDuringConcurrentCommit() {
        UUID vehicleId = vehicle("ACTIVE");
        alert(vehicleId, 1, "HIGH", "OPEN", NOW);
        sample(vehicleId, NOW);
        SelectCounter.COUNT.set(0);
        SelectCounter.BEFORE_THIRD_SELECT.set(() -> CompletableFuture.runAsync(() -> {
            UUID concurrentVehicle = vehicle("DISABLED");
            alert(concurrentVehicle, 2, "CRITICAL", "OPEN", NOW);
            sample(concurrentVehicle, NOW);
        }).orTimeout(10, TimeUnit.SECONDS).join());

        DashboardResponse snapshot = service.getDashboard();
        assertThat(snapshot.totalVehicles()).isEqualTo(1);
        assertThat(snapshot.openAlerts()).isEqualTo(1);
        assertThat(snapshot.recentlyReportingVehicles()).isEqualTo(1);
        assertThat(snapshot.relevantAlerts()).hasSize(1);
        DashboardResponse next = service.getDashboard();
        assertThat(next.totalVehicles()).isEqualTo(2);
        assertThat(next.openAlerts()).isEqualTo(2);
        assertThat(next.recentlyReportingVehicles()).isEqualTo(2);
        assertThat(next.relevantAlerts()).hasSize(2);
    }

    @Test
    void inspectsReportingPlanOnRepresentativeHistory() {
        String index = jdbc.queryForObject("""
                SELECT indexdef FROM pg_indexes
                WHERE indexname = 'ix_telemetry_samples_observed_at_vehicle'
                """, String.class);
        assertThat(index).contains("(observed_at, vehicle_id)");
        UUID vehicleId = vehicle("ACTIVE");
        jdbc.update("""
                INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                    received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                    odometer_km, latitude, longitude)
                SELECT md5('history-' || n)::uuid, ?, n, ?::timestamptz - n * interval '2 seconds',
                    ?, ?, 70, 90, 12, 1000, 41, 12 FROM generate_series(1, 10000) n
                """, vehicleId, Timestamp.from(NOW), Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.execute("ANALYZE telemetry_samples");
        assertThat(samples.countDistinctReportingVehicles(CUTOFF, NOW)).isEqualTo(1);
        String generatedSql = SelectCounter.LAST_SQL.get();
        assertThat(generatedSql).containsIgnoringCase("count(distinct")
                .contains("vehicle_id").contains("observed_at");
        List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + generatedSql,
                String.class, Timestamp.from(CUTOFF), Timestamp.from(NOW));
        System.out.println("FP-037 JPA reporting SQL: " + generatedSql + "\nQuery plan:\n" +
                String.join("\n", plan));
    }

    @Test
    void migrationUpgradesV4AndPreservesExistingData() {
        String schema = "dashboard_upgrade";
        try {
            Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema)
                    .locations("classpath:db/migration").target("4").load().migrate();
            UUID vehicleId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO dashboard_upgrade.vehicles (id, external_code, plate, status,
                        service_interval_km, next_service_at_km, created_at)
                    VALUES (?, 'UPGRADE', 'FP037', 'ACTIVE', 15000, 90000, ?)
                    """, vehicleId, Timestamp.from(NOW));
            assertThat(Flyway.configure().dataSource(jdbc.getDataSource()).schemas(schema)
                    .locations("classpath:db/migration").load().migrate().migrationsExecuted)
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dashboard_upgrade.vehicles",
                    Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'dashboard_upgrade'
                        AND indexname = 'ix_telemetry_samples_observed_at_vehicle'
                    """, Long.class)).isEqualTo(1L);
        } finally {
            jdbc.execute("DROP SCHEMA IF EXISTS dashboard_upgrade CASCADE");
        }
    }

    private UUID vehicle(String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                    next_service_at_km, created_at) VALUES (?, ?, ?, ?, 15000, 90000, ?)
                """, id, id.toString(), id.toString().substring(0, 8), status, Timestamp.from(NOW));
        return id;
    }

    private UUID sample(UUID vehicleId, Instant observed) {
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                    received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                    odometer_km, latitude, longitude)
                VALUES (?, ?, 1, ?, ?, ?, 70, 90, 12, 1000, 41, 12)
                """, messageId, vehicleId, Timestamp.from(observed), Timestamp.from(NOW),
                Timestamp.from(NOW));
        return messageId;
    }

    private UUID alert(UUID vehicleId, int id, String severity, String status, Instant created) {
        UUID alertId = new UUID(0, id);
        UUID messageId = sample(vehicleId, NOW.minusSeconds(120));
        jdbc.update("""
                INSERT INTO maintenance_alerts (id, vehicle_id, source_message_id, type, severity,
                    status, description, created_at, acknowledged_at, closed_at)
                VALUES (?, ?, ?, 'SERVICE_DUE', ?, ?, 'Service due', ?, ?, ?)
                """, alertId, vehicleId, messageId, severity, status, Timestamp.from(created),
                status.equals("ACKNOWLEDGED") ? Timestamp.from(created) : null,
                status.equals("CLOSED") ? Timestamp.from(created) : null);
        return alertId;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
        @Bean
        static SelectCounter selectCounter() {
            return new SelectCounter();
        }
    }

    /** Conta esecuzioni SQL reali sul datasource condiviso da JDBC e JPA. */
    static class SelectCounter implements BeanPostProcessor {
        static final AtomicInteger COUNT = new AtomicInteger();
        static final AtomicReference<String> LAST_SQL = new AtomicReference<>();
        static final AtomicReference<Runnable> BEFORE_THIRD_SELECT = new AtomicReference<>();

        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof DataSource)) {
                return bean;
            }
            return Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        Object result = invoke(bean, method, args);
                        return result instanceof Connection connection
                                ? connectionProxy(connection)
                                : result;
                    });
        }

        private Connection connectionProxy(Connection connection) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        Object result = invoke(connection, method, args);
                        if (result instanceof Statement statement) {
                            String sql = args != null && args.length > 0
                                    && args[0] instanceof String
                                            ? (String) args[0]
                                            : null;
                            Class<?> type = statement instanceof PreparedStatement
                                    ? PreparedStatement.class
                                    : Statement.class;
                            return Proxy.newProxyInstance(type.getClassLoader(),
                                    new Class<?>[]{type},
                                    (statementProxy, operation, parameters) -> {
                                        String executed = sql != null
                                                ? sql
                                                : parameters != null && parameters.length > 0
                                                        && parameters[0] instanceof String
                                                                ? (String) parameters[0]
                                                                : "";
                                        if (operation.getName().startsWith("execute") &&
                                                executed.stripLeading()
                                                        .toUpperCase(java.util.Locale.ROOT)
                                                        .startsWith("SELECT")) {
                                            int count = COUNT.incrementAndGet();
                                            LAST_SQL.set(executed);
                                            if (count == 3) {
                                                Runnable hook = BEFORE_THIRD_SELECT.getAndSet(null);
                                                if (hook != null) {
                                                    hook.run();
                                                }
                                            }
                                        }
                                        return invoke(statement, operation, parameters);
                                    });
                        }
                        return result;
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }
    }
}
