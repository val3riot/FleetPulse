package it.fleetpulse.processor.telemetry.persistence;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
public abstract class PostgreSqlIntegrationSupport {
    @Container
    protected static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer("postgres:17.10-alpine3.23")
                    .withDatabaseName("fleetpulse_processor_test")
                    .withUsername("fleetpulse")
                    .withPassword("fleetpulse_test");

    @DynamicPropertySource
    static void postgresProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                POSTGRESQL::getJdbcUrl
        );
        registry.add(
                "spring.datasource.username",
                POSTGRESQL::getUsername
        );
        registry.add(
                "spring.datasource.password",
                POSTGRESQL::getPassword
        );
    }

}
