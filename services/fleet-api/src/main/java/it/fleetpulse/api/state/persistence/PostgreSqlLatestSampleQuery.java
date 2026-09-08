package it.fleetpulse.api.state.persistence;

import it.fleetpulse.api.state.LatestSampleQuery;
import it.fleetpulse.api.state.LatestVehicleState;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgreSqlLatestSampleQuery implements LatestSampleQuery {
    private final JdbcTemplate jdbc;

    public PostgreSqlLatestSampleQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LatestVehicleState> findByVehicleId(UUID vehicleId) {
        return jdbc.query("""
            SELECT vehicle_id, sequence_number, observed_at, speed_kmh,
                   engine_temperature_c, battery_voltage, odometer_km, latitude, longitude
            FROM telemetry_samples
            WHERE vehicle_id = ?
            ORDER BY observed_at DESC, sequence_number DESC, id DESC
            LIMIT 1
            """, (row, index) -> new LatestVehicleState(
                row.getObject("vehicle_id", UUID.class), row.getLong("sequence_number"),
                row.getTimestamp("observed_at").toInstant(), row.getDouble("speed_kmh"),
                row.getDouble("engine_temperature_c"), row.getDouble("battery_voltage"),
                row.getLong("odometer_km"), row.getDouble("latitude"),
                row.getDouble("longitude")), vehicleId).stream().findFirst();
    }
}
