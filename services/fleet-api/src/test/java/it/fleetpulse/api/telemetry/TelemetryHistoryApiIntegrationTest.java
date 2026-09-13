package it.fleetpulse.api.telemetry;

import com.jayway.jsonpath.JsonPath;
import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class TelemetryHistoryApiIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID OTHER_VEHICLE_ID =
        UUID.fromString("4b2c1534-cfcc-4fdd-9057-90558ef2d234");
    private static final Instant FROM = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T11:00:00Z");
    private static final String PATH = "/api/v1/vehicles/" + VEHICLE_ID + "/telemetry";

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM telemetry_samples");
        jdbc.update("DELETE FROM vehicles");
        insertVehicle(VEHICLE_ID, "HISTORY-1", "FP032AA");
        insertVehicle(OTHER_VEHICLE_ID, "HISTORY-2", "FP032BB");
    }

    @Test
    void usesAnInclusiveRangeAndKeepsVehiclesIsolated() throws Exception {
        insertSample(VEHICLE_ID, FROM.minusMillis(1), 1, 10);
        long fromId = insertSample(VEHICLE_ID, FROM, 2, 20);
        long middleId = insertSample(VEHICLE_ID, FROM.plusSeconds(30), 3, 30);
        long toId = insertSample(VEHICLE_ID, TO, 4, 40);
        insertSample(VEHICLE_ID, TO.plusMillis(1), 5, 50);
        insertSample(OTHER_VEHICLE_ID, FROM.plusSeconds(30), 6, 60);

        MvcResult result = validGet().andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.content.length()").value(3))
            .andExpect(jsonPath("$.content[0].id").value(toId))
            .andExpect(jsonPath("$.content[1].id").value(middleId))
            .andExpect(jsonPath("$.content[2].id").value(fromId))
            .andExpect(jsonPath("$.content[0].vehicleId").value(VEHICLE_ID.toString()))
            .andExpect(jsonPath("$.content[0].sequenceNumber").value(4))
            .andExpect(jsonPath("$.content[0].observedAt").value(TO.toString()))
            .andExpect(jsonPath("$.content[0].speedKmh").value(40.0))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.totalElements").value(3))
            .andExpect(jsonPath("$.totalPages").value(1)).andReturn();

        List<String> vehicles =
            JsonPath.read(result.getResponse().getContentAsString(), "$.content[*].vehicleId");
        assertThat(vehicles).containsOnly(VEHICLE_ID.toString());
    }

    @Test
    void paginatesEqualTimestampsWithoutDuplicatesOrGaps() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int sequence = 1; sequence <= 5; sequence++) {
            ids.add(insertSample(VEHICLE_ID, FROM.plusSeconds(10), sequence, sequence));
        }

        List<Long> firstPage = ids(getPage(0, 2, "observedAt,asc"));
        List<Long> secondPage = ids(getPage(1, 2, "observedAt,asc"));
        List<Long> thirdPage = ids(getPage(2, 2, "observedAt,asc"));

        assertThat(firstPage).containsExactlyElementsOf(ids.subList(0, 2));
        assertThat(secondPage).containsExactlyElementsOf(ids.subList(2, 4));
        assertThat(thirdPage).containsExactly(ids.get(4));
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);

        assertThat(ids(getPage(0, 5, "observedAt,desc")))
            .containsExactlyElementsOf(ids.reversed());
    }

    @Test
    void returnsAValidEmptyPageForAnExistingVehicle() throws Exception {
        validGet().andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.totalElements").value(0))
            .andExpect(jsonPath("$.totalPages").value(0))
            .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void distinguishesMissingVehicleAndInvalidRange() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vehicleId}/telemetry", UUID.randomUUID())
                .param("from", FROM.toString()).param("to", TO.toString()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VEHICLE_NOT_FOUND"));

        mockMvc.perform(get(PATH).param("from", TO.toString()).param("to", FROM.toString()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("REQUEST_INVALID_TIME_RANGE"));
    }

    @Test
    void queryPlanCanUseTheHistoryIndex() {
        insertSample(VEHICLE_ID, FROM.plusSeconds(10), 1, 10);

        List<String> plan = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            try (var setting = connection.createStatement()) {
                setting.execute("SET enable_seqscan = off");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                EXPLAIN (COSTS OFF)
                SELECT * FROM telemetry_samples
                WHERE vehicle_id = ? AND observed_at BETWEEN ? AND ?
                ORDER BY observed_at DESC, id DESC
                LIMIT 50
                """)) {
                statement.setObject(1, VEHICLE_ID);
                statement.setTimestamp(2, Timestamp.from(FROM));
                statement.setTimestamp(3, Timestamp.from(TO));
                try (ResultSet rows = statement.executeQuery()) {
                    List<String> lines = new ArrayList<>();
                    while (rows.next()) {
                        lines.add(rows.getString(1));
                    }
                    return lines;
                }
            } finally {
                try (var reset = connection.createStatement()) {
                    reset.execute("RESET enable_seqscan");
                }
            }
        });

        assertThat(String.join("\n", plan))
            .contains("ix_telemetry_samples_vehicle_observed_at");
    }

    private org.springframework.test.web.servlet.ResultActions validGet() throws Exception {
        return mockMvc.perform(
            get(PATH).param("from", FROM.toString()).param("to", TO.toString()));
    }

    private MvcResult getPage(int page, int size, String sort) throws Exception {
        return mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString())
                .param("page", Integer.toString(page)).param("size", Integer.toString(size))
                .param("sort", sort)).andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(size))
            .andExpect(jsonPath("$.totalElements").value(5)).andReturn();
    }

    private List<Long> ids(MvcResult result) throws Exception {
        List<Number> ids = JsonPath.read(
            result.getResponse().getContentAsString(), "$.content[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private void insertVehicle(UUID id, String externalCode, String plate) {
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                                  next_service_at_km, created_at)
            VALUES (?, ?, ?, 'ACTIVE', 15000, 90000, ?)
            """, id, externalCode, plate, Timestamp.from(FROM));
    }

    private long insertSample(UUID vehicleId, Instant observedAt, long sequence, double speed) {
        return jdbc.queryForObject("""
            INSERT INTO telemetry_samples
                (message_id, vehicle_id, sequence_number, observed_at, received_at, processed_at,
                 speed_kmh, engine_temperature_c, battery_voltage, odometer_km, latitude, longitude)
            VALUES (?, ?, ?, ?, ?, ?, ?, 91.8, 12.6, 85312, ?, 12.4964)
            RETURNING id
            """, Long.class, UUID.randomUUID(), vehicleId, sequence, Timestamp.from(observedAt),
            Timestamp.from(observedAt.plusMillis(83)),
            Timestamp.from(observedAt.plusMillis(150)), speed, 41.9028);
    }
}
