package it.fleetpulse.api.vehicle;

import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Import(VehicleDatabaseUnavailableIntegrationTest.FixedClockConfiguration.class)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class VehicleDatabaseUnavailableIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");

    @Container
    private static final PostgreSQLContainer POSTGRESQL =
        new PostgreSQLContainer("postgres:17.10-alpine3.23").withDatabaseName(
                "fleetpulse_unavailable_test").withUsername("fleetpulse")
            .withPassword("fleetpulse_test");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VehicleRepository repository;

    /**
     * Configura datasource e timeout ridotti per il test isolato di indisponibilità.
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "1000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    /**
     * Arresta PostgreSQL e verifica il mapping reale dell'errore in un 503 uniforme.
     */
    @Test
    @DisplayName("PostgreSQL indisponibile produce SERVICE_UNAVAILABLE reale")
    void mapsStoppedDatabaseToServiceUnavailable() throws Exception {
        assertThat(repository.count()).isZero();
        POSTGRESQL.stop();

        mockMvc.perform(get("/api/v1/vehicles/{vehicleId}",
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.message").value("A required service is temporarily unavailable"))
            .andExpect(
                jsonPath("$.path").value("/api/v1/vehicles/97e194a8-64b3-4885-b1e6-25fd482f58c0"))
            .andExpect(jsonPath("$.details").isArray()).andExpect(jsonPath("$.details").isEmpty())
            .andExpect(jsonPath("$.error").doesNotExist());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        /**
         * Fornisce un timestamp deterministico alla risposta di errore.
         */
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

    }
}
