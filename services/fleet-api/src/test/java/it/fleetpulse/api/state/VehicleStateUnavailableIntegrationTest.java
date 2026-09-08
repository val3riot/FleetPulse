package it.fleetpulse.api.state;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class VehicleStateUnavailableIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine3.23")
        .withDatabaseName("fleetpulse_test").withUsername("fleetpulse").withPassword("test");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.8-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "1000");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void stoppedRedisStillReturnsStateButStoppedPostgresProduces503() throws Exception {
        UUID id = UUID.randomUUID();
        Instant observed = Instant.parse("2026-08-01T10:00:00Z");
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                next_service_at_km, created_at)
            VALUES (?, 'DOWN-1', 'FP031BB', 'ACTIVE', 15000, 90000, ?)
            """, id, Timestamp.from(observed));
        jdbc.update("""
            INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                odometer_km, latitude, longitude)
            VALUES (?, ?, 42, ?, ?, ?, 72.4, 91.8, 12.6, 85312, 41.9, 12.4)
            """, UUID.randomUUID(), id, Timestamp.from(observed), Timestamp.from(observed),
            Timestamp.from(observed));
        String path = "/api/v1/vehicles/" + id + "/state";
        REDIS.stop();
        mvc.perform(get(path)).andExpect(status().isOk())
            .andExpect(jsonPath("$.lastSequenceNumber").value(42))
            .andExpect(jsonPath("$.lastSeenAt").value(observed.toString()))
            .andExpect(jsonPath("$.stale").value(true));
        POSTGRES.stop();
        mvc.perform(get(path)).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
