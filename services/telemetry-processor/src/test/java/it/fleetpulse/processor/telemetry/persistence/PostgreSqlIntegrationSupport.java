package it.fleetpulse.processor.telemetry.persistence;

import it.fleetpulse.processor.telemetry.redis.RedisLatestStateProjection;
import it.fleetpulse.processor.telemetry.projection.ProjectionUpdateResult;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers
public abstract class PostgreSqlIntegrationSupport {
    // This test profile excludes Redis auto-configuration; adapter tests use a real Redis separately.
    @MockitoBean
    protected RedisLatestStateProjection latestStateProjection;

    @BeforeEach
    void stubProjection() {
        when(latestStateProjection.updateIfNewer(any()))
            .thenReturn(ProjectionUpdateResult.UPDATED);
    }

    @Container
    protected static final PostgreSQLContainer POSTGRESQL =
        new PostgreSQLContainer("postgres:17.10-alpine3.23").withDatabaseName(
            "fleetpulse_processor_test").withUsername("fleetpulse").withPassword("fleetpulse_test");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRESQL::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRESQL::getUsername);
        registry.add("spring.datasource.password", POSTGRESQL::getPassword);
    }

}
