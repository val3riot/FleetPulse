package it.fleetpulse.api.state;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.api.state.persistence.PostgreSqlLatestSampleQuery;
import it.fleetpulse.api.state.redis.RedisLatestStateProjection;
import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import it.fleetpulse.api.vehicle.VehicleRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(VehicleStateRecoveryIntegrationTest.FixedClock.class)
class VehicleStateRecoveryIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant OBSERVED = NOW.minusSeconds(30);
    private static final UUID ID = new UUID(0, 390);
    private static final String KEY = "vehicle:last:" + ID;
    private static final String PATH = "/api/v1/vehicles/" + ID + "/state";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.8-alpine")
        .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379);

    @Container
    static final ToxiproxyContainer TOXIPROXY =
        new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(NETWORK).dependsOn(REDIS);

    private static Proxy proxy;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) throws IOException {
        proxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
            .createProxy("redis", "0.0.0.0:8666", "redis:6379");
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> TOXIPROXY.getMappedPort(8666));
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry metrics;
    @Autowired private RedisLatestStateProjection projection;
    @MockitoSpyBean private PostgreSqlLatestSampleQuery samples;
    @MockitoSpyBean private VehicleRepository vehicles;

    @BeforeEach
    void fixture() throws Exception {
        reset(samples);
        proxy.enable();
        REDIS.execInContainer("redis-cli", "FLUSHDB");
        awaitConnection();
        jdbc.update("DELETE FROM maintenance_alerts");
        jdbc.update("DELETE FROM telemetry_samples");
        jdbc.update("DELETE FROM vehicles");
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                next_service_at_km, created_at)
            VALUES (?, 'RECOVERY', 'FP039', 'DISABLED', 15000, 90000, ?)
            """, ID, Timestamp.from(OBSERVED));
        jdbc.update("""
            INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                odometer_km, latitude, longitude)
            VALUES (?, ?, 42, ?, ?, ?, 72.4, 91.8, 12.6, 85312, 41.9, 12.4)
            """, UUID.randomUUID(), ID, Timestamp.from(OBSERVED), Timestamp.from(OBSERVED),
            Timestamp.from(OBSERVED));
    }

    @AfterAll
    static void closeNetwork() {
        TOXIPROXY.stop();
        REDIS.stop();
        NETWORK.close();
    }

    @Test
    void reconnectsRepairsAndServesCacheHitAfterConnectionOutage() throws Exception {
        double failures = count("failures");
        double repairs = count("repair.failures");
        double fallbacks = count("fallback");
        double misses = count("misses");
        proxy.disable();
        String response;
        try {
            response = state();
            assertThat(count("failures")).isEqualTo(failures + 1);
            assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks + 1);
            assertThat(count("misses")).isEqualTo(misses);
            assertThat(redisValue()).isEmpty();
        } finally {
            proxy.enable();
        }
        assertRecovered(response);
    }

    @Test
    void realCommandTimeoutFallsBackAndRecoversAfterNetworkFaultIsRemoved() throws Exception {
        proxy.toxics().timeout("blackhole", ToxicDirection.DOWNSTREAM, 0);
        double failures = count("failures");
        double repairs = count("repair.failures");
        double fallbacks = count("fallback");
        String response;
        try {
            // TCP rimane aperto, ma Redis non può consegnare risposte al client.
            assertThatThrownBy(() -> projection.findByVehicleId(ID))
                .isInstanceOf(LatestStateProjectionException.class)
                .hasCauseInstanceOf(QueryTimeoutException.class);
            response = state();
            assertThat(count("failures")).isEqualTo(failures + 1);
            assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks + 1);
        } finally {
            proxy.toxics().get("blackhole").remove();
        }
        // Un comando già inviato può essere applicato anche dopo un timeout client.
        awaitConnection();
        REDIS.execInContainer("redis-cli", "DEL", KEY);
        assertRecovered(response);
    }

    @Test
    void repairConnectionFailureAfterSuccessfulMissDoesNotChangePostgresResponse()
        throws Exception {
        double failures = count("failures");
        double repairs = count("repair.failures");
        double misses = count("misses");
        double fallbacks = count("fallback");
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            proxy.disable();
            return result;
        }).when(samples).findByVehicleId(any());
        String response;
        try {
            response = state();
            assertThat(count("failures")).isEqualTo(failures);
            assertThat(count("misses")).isEqualTo(misses + 1);
            assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks + 1);
            assertThat(redisValue()).isEmpty();
        } finally {
            reset(samples);
            proxy.enable();
        }
        assertRecovered(response);
    }

    private void assertRecovered(String response) throws Exception {
        awaitConnection();
        double misses = count("misses");
        double fallbacks = count("fallback");
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
        assertThat(count("misses")).isEqualTo(misses + 1);
        assertThat(count("fallback")).isEqualTo(fallbacks + 1);
        assertThat(redisValue()).contains("lastSeenAt", OBSERVED.toString())
            .doesNotContain("stale");
        long ttl = Long.parseLong(REDIS.execInContainer("redis-cli", "PTTL", KEY)
            .getStdout().trim());
        assertThat(ttl).isBetween(1L, 300_000L);
        double hits = count("hits");
        clearInvocations(samples, vehicles);
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
        assertThat(count("hits")).isEqualTo(hits + 1);
        assertThat(count("fallback")).isEqualTo(fallbacks + 1);
        verifyNoInteractions(samples, vehicles);
    }

    private String state() throws Exception {
        return mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicleId").value(ID.toString()))
            .andExpect(jsonPath("$.lastSequenceNumber").value(42))
            .andExpect(jsonPath("$.lastSeenAt").value(OBSERVED.toString()))
            .andExpect(jsonPath("$.stale").value(false))
            .andReturn().getResponse().getContentAsString();
    }

    private void awaitConnection() {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .ignoreExceptions().until(() -> projection.findByVehicleId(new UUID(0, 391)).isEmpty());
    }

    private String redisValue() throws Exception {
        return REDIS.execInContainer("redis-cli", "GET", KEY).getStdout().trim();
    }

    private double count(String name) {
        return metrics.get("fleetpulse.api.cache." + name).counter().count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
