package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.TelemetryEventProcessingService;
import it.fleetpulse.processor.telemetry.TelemetrySource;
import it.fleetpulse.processor.telemetry.vehicle.VehicleEligibilityGuard;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionObservability;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.ProjectionUpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.processor.telemetry.redis.LatestStateProjectionProperties;
import it.fleetpulse.processor.telemetry.redis.RedisLatestStateCodec;
import it.fleetpulse.processor.telemetry.redis.RedisLatestStateProjection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({TelemetryEventProcessingService.class,
    TelemetrySampleMapper.class,
    TelemetrySampleWriter.class,
    TelemetryPersistenceFailureClassifier.class,
    TelemetrySamplePersistenceIntegrationTest.TestClockConfiguration.class})
@ActiveProfiles("test")
class TelemetrySamplePersistenceIntegrationTest extends PostgreSqlIntegrationSupport {

    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-01T10:15:30Z");

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-01T10:15:30.083Z");

    private static final Instant PROCESSED_AT = Instant.parse("2026-08-01T10:15:30.150Z");
    private static final TelemetrySource SOURCE = new TelemetrySource("telemetry.raw.v1", 1, 42L);

    @Autowired
    private TelemetrySampleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TelemetryEventProcessingService service;

    @Autowired
    private TelemetrySampleWriter writer;

    @Autowired
    private TelemetrySampleMapper mapper;

    @Autowired
    private Clock clock;

    @Autowired
    private TelemetryPersistenceFailureClassifier failureClassifier;

    @Autowired
    private VehicleEligibilityGuard eligibilityGuard;

    @Autowired
    private LatestStateProjectionObservability projectionObservability;

    @BeforeEach
    void insertVehicle() {
        jdbcTemplate.update("""
                insert into vehicles (
                    id,
                    external_code,
                    plate,
                    status,
                    service_interval_km,
                    next_service_at_km,
                    created_at
                ) values (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, VEHICLE_ID, "VAN-PERSIST", "FP100AA", 15_000, 90_000L,
            OffsetDateTime.ofInstant(Instant.parse("2026-08-01T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void persistsAndReadsCompleteTelemetrySample() {
        TelemetrySampleEntity saved = repository.saveAndFlush(entity());

        assertThat(saved.getId()).isNotNull();

        entityManager.clear();

        TelemetrySampleEntity reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getMessageId()).isEqualTo(MESSAGE_ID);
        assertThat(reloaded.getVehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(reloaded.getSequenceNumber()).isEqualTo(42);
        assertThat(reloaded.getObservedAt()).isEqualTo(OBSERVED_AT);
        assertThat(reloaded.getReceivedAt()).isEqualTo(RECEIVED_AT);
        assertThat(reloaded.getProcessedAt()).isEqualTo(PROCESSED_AT);
        assertThat(reloaded.getSpeedKmh()).isEqualTo(72.4);
        assertThat(reloaded.getEngineTemperatureC()).isEqualTo(91.8);
        assertThat(reloaded.getBatteryVoltage()).isEqualTo(12.6);
        assertThat(reloaded.getOdometerKm()).isEqualTo(85_312);
        assertThat(reloaded.getLatitude()).isEqualTo(41.9028);
        assertThat(reloaded.getLongitude()).isEqualTo(12.4964);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void persistsTelemetryEventThroughApplicationService() {
        TelemetryEvent event =
            new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42, OBSERVED_AT,
                RECEIVED_AT, new TelemetryData(72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964));

        when(latestStateProjection.updateIfNewer(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // With no thread-bound transaction this query sees only committed PostgreSQL data.
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from telemetry_samples where message_id = ?", Long.class, MESSAGE_ID))
                .isEqualTo(1);
            return ProjectionUpdateResult.UPDATED;
        });

        try {
            service.handle(event, SOURCE);
            verify(latestStateProjection).updateIfNewer(any());
            assertThat(repository.findAll()).singleElement().satisfies(sample -> {
                assertThat(sample.getMessageId()).isEqualTo(MESSAGE_ID);
                assertThat(sample.getVehicleId()).isEqualTo(VEHICLE_ID);
                assertThat(sample.getProcessedAt()).isEqualTo(PROCESSED_AT);
                assertThat(sample.getSpeedKmh()).isEqualTo(72.4);
            });
        } finally {
            jdbcTemplate.update("delete from telemetry_samples where message_id = ?", MESSAGE_ID);
            jdbcTemplate.update("delete from vehicles where id = ?", VEHICLE_ID);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void redisFailureDoesNotUndoCommittedSample() {
        var event = new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42,
            OBSERVED_AT, RECEIVED_AT, new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964));
        when(latestStateProjection.updateIfNewer(any()))
            .thenThrow(new LatestStateProjectionException("Redis unavailable"));
        try {
            assertDoesNotThrow(() -> service.handle(event, SOURCE));
            assertThat(repository.count()).isEqualTo(1);
            service.handle(event, SOURCE);
            verify(latestStateProjection).updateIfNewer(any());
        } finally {
            jdbcTemplate.update("delete from telemetry_samples where message_id = ?", MESSAGE_ID);
            jdbcTemplate.update("delete from vehicles where id = ?", VEHICLE_ID);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void stoppedRedisDoesNotPreventPostgresCommitOrProcessingCompletion() {
        var event = new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42,
            OBSERVED_AT, RECEIVED_AT, new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964));
        var registry = new SimpleMeterRegistry();
        try (var container = new GenericContainer<>("redis:8.2.8-alpine").withExposedPorts(6379)) {
            container.start();
            var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500))
                .shutdownTimeout(Duration.ZERO).build();
            var factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(container.getHost(), container.getMappedPort(6379)), client);
            try {
                factory.afterPropertiesSet();
                var template = new StringRedisTemplate(factory);
                var adapter = new RedisLatestStateProjection(template, new RedisLatestStateCodec(),
                    new LatestStateProjectionProperties(Duration.ofMinutes(5), 1));
                assertThat(adapter.findByVehicleId(VEHICLE_ID)).isEmpty();
                container.stop();

                var processor = new TelemetryEventProcessingService(writer, mapper, clock, failureClassifier,
                    eligibilityGuard, adapter, new LatestStateProjectionObservability(registry));

                assertDoesNotThrow(() -> processor.handle(event, SOURCE));
                assertThat(repository.findAll()).singleElement()
                    .extracting(TelemetrySampleEntity::getMessageId).isEqualTo(MESSAGE_ID);
                assertThat(registry.get("fleetpulse.redis.update.failures").counter().count()).isEqualTo(1);
                assertThat(registry.get("fleetpulse.telemetry.latest_state.updates")
                    .tag("outcome", "failed").counter().count()).isEqualTo(1);
            } finally {
                factory.destroy();
            }
        } finally {
            registry.close();
            jdbcTemplate.update("delete from telemetry_samples where message_id = ?", MESSAGE_ID);
            jdbcTemplate.update("delete from vehicles where id = ?", VEHICLE_ID);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void treatsRepeatedMessageIdAsSingleSample() {
        TelemetryEvent event =
            new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42, OBSERVED_AT,
                RECEIVED_AT, new TelemetryData(72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964));

        try {
            service.handle(event, SOURCE);
            service.handle(event, SOURCE);

            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findAll()).singleElement()
                .extracting(TelemetrySampleEntity::getMessageId).isEqualTo(MESSAGE_ID);
        } finally {
            jdbcTemplate.update("delete from telemetry_samples where message_id = ?", MESSAGE_ID);
            jdbcTemplate.update("delete from vehicles where id = ?", VEHICLE_ID);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDeliveryCreatesSingleSample() throws Exception {
        TelemetryEvent event =
            new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42, OBSERVED_AT,
                RECEIVED_AT, new TelemetryData(72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Callable<Void> processing = () -> {
            ready.countDown();
            start.await();
            service.handle(event, SOURCE);
            return null;
        };

        try {
            Future<Void> first = executor.submit(processing);
            Future<Void> second = executor.submit(processing);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertThat(repository.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();

            jdbcTemplate.update("delete from telemetry_samples where message_id = ?", MESSAGE_ID);
            jdbcTemplate.update("delete from vehicles where id = ?", VEHICLE_ID);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void replayAfterServiceRestartCreatesSingleSample() {
        TelemetryEvent event =
            new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42, OBSERVED_AT,
                RECEIVED_AT, new TelemetryData(72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964));

        TelemetryEventProcessingService restartedService =
            new TelemetryEventProcessingService(writer, mapper, clock, failureClassifier,
                eligibilityGuard, latestStateProjection, projectionObservability);

        try {
            service.handle(event, SOURCE);
            restartedService.handle(event, SOURCE);

            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findAll()).singleElement()
                .extracting(TelemetrySampleEntity::getMessageId).isEqualTo(MESSAGE_ID);
        } finally {
            jdbcTemplate.update("delete from telemetry_samples where message_id = ?", MESSAGE_ID);
            jdbcTemplate.update("delete from vehicles where id = ?", VEHICLE_ID);
        }
    }

    private static TelemetrySampleEntity entity() {
        return new TelemetrySampleEntity(MESSAGE_ID, VEHICLE_ID, 42, OBSERVED_AT, RECEIVED_AT,
            PROCESSED_AT, 72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964);
    }

    @TestConfiguration
    static class TestClockConfiguration {

        @Bean
        LatestStateProjectionObservability projectionObservability() {
            return new LatestStateProjectionObservability(new SimpleMeterRegistry());
        }

        @Bean
        Clock clock() {
            return Clock.fixed(PROCESSED_AT, ZoneOffset.UTC);
        }

        @Bean
        VehicleEligibilityGuard eligibilityGuard() {
            return mock(VehicleEligibilityGuard.class);
        }
    }
}
