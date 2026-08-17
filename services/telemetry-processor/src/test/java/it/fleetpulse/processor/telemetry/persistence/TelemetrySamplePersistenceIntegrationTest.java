package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.TelemetryEventProcessingService;
import it.fleetpulse.processor.telemetry.TelemetrySource;
import it.fleetpulse.processor.telemetry.vehicle.VehicleEligibilityGuard;
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
import static org.springframework.boot.jdbc.test.autoconfigure
        .AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({
        TelemetryEventProcessingService.class,
        TelemetrySampleMapper.class,
        TelemetrySampleWriter.class,
        TelemetryPersistenceFailureClassifier.class,
        TelemetrySamplePersistenceIntegrationTest.TestClockConfiguration.class
})
@ActiveProfiles("test")
class TelemetrySamplePersistenceIntegrationTest
        extends PostgreSqlIntegrationSupport {

    private static final UUID VEHICLE_ID =
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    private static final UUID MESSAGE_ID =
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");

    private static final Instant OBSERVED_AT =
            Instant.parse("2026-08-01T10:15:30Z");

    private static final Instant RECEIVED_AT =
            Instant.parse("2026-08-01T10:15:30.083Z");

    private static final Instant PROCESSED_AT =
            Instant.parse("2026-08-01T10:15:30.150Z");
    private static final TelemetrySource SOURCE =
            new TelemetrySource("telemetry.raw.v1", 1, 42L);

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

    @BeforeEach
    void insertVehicle() {
        jdbcTemplate.update(
                """
                insert into vehicles (
                    id,
                    external_code,
                    plate,
                    status,
                    service_interval_km,
                    next_service_at_km,
                    created_at
                ) values (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """,
                VEHICLE_ID,
                "VAN-PERSIST",
                "FP100AA",
                15_000,
                90_000L,
                OffsetDateTime.ofInstant(
                        Instant.parse("2026-08-01T08:00:00Z"),
                        ZoneOffset.UTC
                )
        );
    }

    @Test
    void persistsAndReadsCompleteTelemetrySample() {
        TelemetrySampleEntity saved =
                repository.saveAndFlush(entity());

        assertThat(saved.getId()).isNotNull();

        entityManager.clear();

        TelemetrySampleEntity reloaded =
                repository.findById(saved.getId()).orElseThrow();

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
    void persistsTelemetryEventThroughApplicationService() {
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEventVersions.V1,
                MESSAGE_ID,
                VEHICLE_ID,
                42,
                OBSERVED_AT,
                RECEIVED_AT,
                new TelemetryData(
                        72.4,
                        91.8,
                        12.6,
                        85_312,
                        41.9028,
                        12.4964
                )
        );

        service.handle(event, SOURCE);

        entityManager.clear();

        assertThat(repository.findAll())
                .singleElement()
                .satisfies(sample -> {
                    assertThat(sample.getMessageId())
                            .isEqualTo(MESSAGE_ID);
                    assertThat(sample.getVehicleId())
                            .isEqualTo(VEHICLE_ID);
                    assertThat(sample.getProcessedAt())
                            .isEqualTo(PROCESSED_AT);
                    assertThat(sample.getSpeedKmh())
                            .isEqualTo(72.4);
                });
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void treatsRepeatedMessageIdAsSingleSample() {
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEventVersions.V1,
                MESSAGE_ID,
                VEHICLE_ID,
                42,
                OBSERVED_AT,
                RECEIVED_AT,
                new TelemetryData(
                        72.4,
                        91.8,
                        12.6,
                        85_312,
                        41.9028,
                        12.4964
                )
        );

        try {
            service.handle(event, SOURCE);
            service.handle(event, SOURCE);

            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findAll())
                    .singleElement()
                    .extracting(TelemetrySampleEntity::getMessageId)
                    .isEqualTo(MESSAGE_ID);
        } finally {
            jdbcTemplate.update(
                    "delete from telemetry_samples where message_id = ?",
                    MESSAGE_ID
            );
            jdbcTemplate.update(
                    "delete from vehicles where id = ?",
                    VEHICLE_ID
            );
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDeliveryCreatesSingleSample() throws Exception {
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEventVersions.V1,
                MESSAGE_ID,
                VEHICLE_ID,
                42,
                OBSERVED_AT,
                RECEIVED_AT,
                new TelemetryData(
                        72.4,
                        91.8,
                        12.6,
                        85_312,
                        41.9028,
                        12.4964
                )
        );

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

            assertThat(
                    ready.await(10, TimeUnit.SECONDS)
            ).isTrue();

            start.countDown();

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            assertThat(repository.count()).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();

            jdbcTemplate.update(
                    "delete from telemetry_samples where message_id = ?",
                    MESSAGE_ID
            );
            jdbcTemplate.update(
                    "delete from vehicles where id = ?",
                    VEHICLE_ID
            );
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void replayAfterServiceRestartCreatesSingleSample() {
        TelemetryEvent event = new TelemetryEvent(
                TelemetryEventVersions.V1,
                MESSAGE_ID,
                VEHICLE_ID,
                42,
                OBSERVED_AT,
                RECEIVED_AT,
                new TelemetryData(
                        72.4,
                        91.8,
                        12.6,
                        85_312,
                        41.9028,
                        12.4964
                )
        );

        TelemetryEventProcessingService restartedService =
                new TelemetryEventProcessingService(
                        writer,
                        mapper,
                        clock,
                        failureClassifier,
                        eligibilityGuard
                );

        try {
            service.handle(event, SOURCE);
            restartedService.handle(event, SOURCE);

            assertThat(repository.count()).isEqualTo(1);
            assertThat(repository.findAll())
                    .singleElement()
                    .extracting(TelemetrySampleEntity::getMessageId)
                    .isEqualTo(MESSAGE_ID);
        } finally {
            jdbcTemplate.update(
                    "delete from telemetry_samples where message_id = ?",
                    MESSAGE_ID
            );
            jdbcTemplate.update(
                    "delete from vehicles where id = ?",
                    VEHICLE_ID
            );
        }
    }

    private static TelemetrySampleEntity entity() {
        return new TelemetrySampleEntity(
                MESSAGE_ID,
                VEHICLE_ID,
                42,
                OBSERVED_AT,
                RECEIVED_AT,
                PROCESSED_AT,
                72.4,
                91.8,
                12.6,
                85_312,
                41.9028,
                12.4964
        );
    }

    @TestConfiguration
    static class TestClockConfiguration {

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
