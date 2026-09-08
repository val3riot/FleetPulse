package it.fleetpulse.api.state;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.api.state.persistence.PostgreSqlLatestSampleQuery;
import it.fleetpulse.api.state.redis.RedisLatestStateCodec;
import it.fleetpulse.api.state.redis.RedisLatestStateProjection;
import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(VehicleStateApiIntegrationTest.FixedClock.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class VehicleStateApiIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private static final UUID ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final String PATH = "/api/v1/vehicles/" + ID + "/state";
    private static final String KEY = "vehicle:last:" + ID;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.8-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired RedisLatestStateProjection projection;
    @Autowired RedisLatestStateCodec codec;
    @Autowired MeterRegistry metrics;
    @MockitoSpyBean PostgreSqlLatestSampleQuery samples;

    @BeforeEach
    void setup() {
        Mockito.reset(samples);
        jdbc.update("DELETE FROM telemetry_samples");
        jdbc.update("DELETE FROM vehicles");
        redis.delete(KEY);
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                                  next_service_at_km, created_at)
            VALUES (?, 'STATE-1', 'FP031AA', 'DISABLED', 15000, 90000, ?)
            """, ID, Timestamp.from(NOW));
    }

    @Test
    void fallbackRepairsAndSubsequentHitReturnsSameContractWithoutHistoryQuery() throws Exception {
        insert(ID, NOW.minusSeconds(30), 42, 72.4);
        double misses = count("misses");
        double fallbacks = count("fallback");
        String first = mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(10))
            .andExpect(jsonPath("$.vehicleId").value(ID.toString()))
            .andExpect(jsonPath("$.lastSequenceNumber").value(42))
            .andExpect(jsonPath("$.lastSeenAt").value(NOW.minusSeconds(30).toString()))
            .andExpect(jsonPath("$.stale").value(false))
            .andExpect(jsonPath("$.speedKmh").value(72.4))
            .andExpect(jsonPath("$.engineTemperatureC").value(91.8))
            .andExpect(jsonPath("$.batteryVoltage").value(12.6))
            .andExpect(jsonPath("$.odometerKm").value(85312))
            .andExpect(jsonPath("$.latitude").value(41.9))
            .andExpect(jsonPath("$.longitude").value(12.4))
            .andReturn().getResponse().getContentAsString();
        assertThat(count("misses")).isEqualTo(misses + 1);
        assertThat(count("fallback")).isEqualTo(fallbacks + 1);
        assertThat(redis.getExpire(KEY)).isBetween(1L, 300L);
        assertThat(redis.opsForValue().get(KEY)).doesNotContain("stale");
        clearInvocations(samples);
        double hits = count("hits");
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(first));
        verifyNoInteractions(samples);
        assertThat(count("hits")).isEqualTo(hits + 1);
    }

    @Test
    void latestSampleOrdersByObservationThenSequenceThenStableId() throws Exception {
        insert(ID, NOW.minusSeconds(2), 999, 10);
        insert(ID, NOW.minusSeconds(1), 2, 20);
        insert(ID, NOW.minusSeconds(1), 2, 30);
        insert(ID, NOW.minusSeconds(1), 1, 40);
        mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.lastSequenceNumber").value(2))
            .andExpect(jsonPath("$.speedKmh").value(30));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "61,true"})
    void missingStateAndMissingVehicleAreDifferentAfterCacheExpires(long ageSeconds,
        boolean stale) throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VEHICLE_STATE_NOT_AVAILABLE"));
        jdbc.update("DELETE FROM vehicles");
        projection.updateIfNewer(state(NOW.minusSeconds(ageSeconds), 42));
        mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.stale").value(stale));
        redis.expire(KEY, java.time.Duration.ofMillis(300));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
            .until(() -> !Boolean.TRUE.equals(redis.hasKey(KEY)));
        mvc.perform(get(PATH)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VEHICLE_NOT_FOUND"));
        mvc.perform(get("/api/v1/vehicles/invalid/state")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
    }

    @Test
    void corruptJsonFallsBackAndIsRepairedWithOriginalStaleTimestamp() throws Exception {
        redis.opsForValue().set(KEY, "{invalid-json}");
        insert(ID, NOW.minusSeconds(61), 42, 72.4);
        double failures = count("failures");
        mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.stale").value(true))
            .andExpect(jsonPath("$.lastSeenAt").value(NOW.minusSeconds(61).toString()));
        assertThat(count("failures")).isEqualTo(failures + 1);
        assertThat(projection.findByVehicleId(ID)).contains(state(NOW.minusSeconds(61), 42));
    }

    @Test
    void repairDoesNotOverwriteNewerConcurrentProjection() throws Exception {
        insert(ID, NOW.minusSeconds(5), 42, 72.4);
        doAnswer(invocation -> {
            var result = invocation.callRealMethod();
            projection.updateIfNewer(state(NOW, 1));
            return result;
        }).when(samples).findByVehicleId(ID);
        mvc.perform(get(PATH)).andExpect(status().isOk());
        assertThat(projection.findByVehicleId(ID)).contains(state(NOW, 1));
    }

    @Test
    void mismatchedVehicleJsonIsNotServedAndCanBeRepaired() throws Exception {
        var other = new LatestVehicleState(UUID.randomUUID(), 99, NOW, 0, 0, 0, 0, 0, 0);
        redis.opsForValue().set(KEY, codec.encode(other));
        insert(ID, NOW.minusSeconds(5), 42, 72.4);
        mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicleId").value(ID.toString()));
        assertThat(projection.findByVehicleId(ID)).contains(state(NOW.minusSeconds(5), 42));
    }

    @Test
    void fallbackDoesNotReadAnotherVehiclesNewerSample() throws Exception {
        UUID other = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                next_service_at_km, created_at)
            VALUES (?, 'OTHER', 'FP031CC', 'ACTIVE', 15000, 90000, ?)
            """, other, Timestamp.from(NOW));
        insert(other, NOW, 999, 99);
        mvc.perform(get(PATH)).andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VEHICLE_STATE_NOT_AVAILABLE"));
        insert(ID, NOW.minusSeconds(1), 42, 72.4);
        mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.lastSequenceNumber").value(42));
    }

    @Test
    void latestStateIndexIsCreatedByMigration() {
        assertThat(jdbc.queryForObject(
            "SELECT success FROM flyway_schema_history WHERE version = '2'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT indexdef FROM pg_indexes
            WHERE indexname = 'ix_telemetry_samples_vehicle_latest_state'
            """, String.class))
            .contains("vehicle_id, observed_at DESC, sequence_number DESC, id DESC");
    }

    void insert(UUID vehicleId, Instant observed, long sequence, double speed) {
        jdbc.update("""
            INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                odometer_km, latitude, longitude)
            VALUES (?, ?, ?, ?, ?, ?, ?, 91.8, 12.6, 85312, 41.9, 12.4)
            """, UUID.randomUUID(), vehicleId, sequence, Timestamp.from(observed),
            Timestamp.from(NOW), Timestamp.from(NOW), speed);
    }

    private LatestVehicleState state(Instant time, long sequence) {
        return new LatestVehicleState(ID, sequence, time, 72.4, 91.8, 12.6, 85312, 41.9, 12.4);
    }

    private double count(String name) {
        return metrics.get("fleetpulse.api.cache." + name).counter().count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean @Primary
        Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
