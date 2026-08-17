package it.fleetpulse.processor.telemetry.vehicle;

import it.fleetpulse.processor.telemetry.persistence.PostgreSqlIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.jdbc.test.autoconfigure
        .AutoConfigureTestDatabase.Replace.NONE;

@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import(PostgreSqlVehicleRegistry.class)
@ActiveProfiles("test")
class PostgreSqlVehicleRegistryIntegrationTest
        extends PostgreSqlIntegrationSupport {

    private static final UUID ACTIVE_VEHICLE_ID = UUID.fromString(
            "97e194a8-64b3-4885-b1e6-25fd482f58c0"
    );

    private static final UUID DISABLED_VEHICLE_ID = UUID.fromString(
            "047eaf55-24cd-43b9-98f5-e9516649a9fd"
    );

    @Autowired
    private PostgreSqlVehicleRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void insertVehicles() {
        insertVehicle(
                ACTIVE_VEHICLE_ID,
                "VAN-ACTIVE",
                "FP100AA",
                "ACTIVE"
        );
        insertVehicle(
                DISABLED_VEHICLE_ID,
                "VAN-DISABLED",
                "FP200BB",
                "DISABLED"
        );
    }

    @Test
    void findsActiveVehicleStatus() {
        assertThat(registry.findStatus(ACTIVE_VEHICLE_ID))
                .contains(VehicleStatus.ACTIVE);
    }

    @Test
    void findsDisabledVehicleStatus() {
        assertThat(registry.findStatus(DISABLED_VEHICLE_ID))
                .contains(VehicleStatus.DISABLED);
    }

    @Test
    void returnsEmptyForUnknownVehicle() {
        assertThat(registry.findStatus(UUID.randomUUID()))
                .isEmpty();
    }

    private void insertVehicle(
            UUID id,
            String externalCode,
            String plate,
            String status
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO vehicles (
                    id,
                    external_code,
                    plate,
                    status,
                    service_interval_km,
                    next_service_at_km,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                externalCode,
                plate,
                status,
                15_000,
                90_000L,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }
}
