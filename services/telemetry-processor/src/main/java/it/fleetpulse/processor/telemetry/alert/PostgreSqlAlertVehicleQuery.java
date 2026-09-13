package it.fleetpulse.processor.telemetry.alert;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgreSqlAlertVehicleQuery implements AlertVehicleQuery {
    private final JdbcClient jdbcClient;

    public PostgreSqlAlertVehicleQuery(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<AlertVehicle> findById(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");

        return jdbcClient.sql("""
                SELECT id, next_service_at_km
                FROM vehicles
                WHERE id = :vehicleId
                """)
            .param("vehicleId", vehicleId)
            .query((resultSet, rowNumber) -> new AlertVehicle(
                resultSet.getObject("id", UUID.class),
                resultSet.getLong("next_service_at_km")))
            .optional();
    }
}
