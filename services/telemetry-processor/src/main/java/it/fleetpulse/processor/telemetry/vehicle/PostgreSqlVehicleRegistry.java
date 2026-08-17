package it.fleetpulse.processor.telemetry.vehicle;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgreSqlVehicleRegistry implements VehicleRegistry {

    private final JdbcClient jdbcClient;

    public PostgreSqlVehicleRegistry(JdbcClient jdbcClient) {
        this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient must not be null");
    }

    @Override
    public Optional<VehicleStatus> findStatus(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");

        return jdbcClient.sql("""
                SELECT status
                FROM vehicles
                WHERE id = :vehicleId
                """).param("vehicleId", vehicleId).query(String.class).optional()
            .map(VehicleStatus::valueOf);
    }
}
