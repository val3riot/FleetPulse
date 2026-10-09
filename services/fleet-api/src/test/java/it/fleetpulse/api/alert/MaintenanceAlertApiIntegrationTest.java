package it.fleetpulse.api.alert;

import com.jayway.jsonpath.JsonPath;
import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class MaintenanceAlertApiIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final UUID VEHICLE = new UUID(0, 100);
    private static final UUID OTHER = new UUID(0, 200);
    private static final Instant START = Instant.parse("2026-10-07T10:00:00Z");
    private static final String GLOBAL = "/api/v1/alerts";
    private static final String SCOPED = "/api/v1/vehicles/" + VEHICLE + "/alerts";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM maintenance_alerts");
        jdbc.update("DELETE FROM telemetry_samples");
        jdbc.update("DELETE FROM vehicles");
        vehicle(VEHICLE, "DISABLED");
        vehicle(OTHER, "ACTIVE");
        alert(1, VEHICLE, "OPEN", "HIGH", START);
        alert(2, VEHICLE, "ACKNOWLEDGED", "LOW", START.plusSeconds(10));
        alert(3, VEHICLE, "OPEN", "HIGH", START.plusSeconds(10));
        alert(4, OTHER, "OPEN", "HIGH", START.plusSeconds(10));
        alert(5, VEHICLE, "CLOSED", "HIGH", START.plusSeconds(20));
    }

    @Test
    void combinesAllFiltersThroughBothRoutesWithInclusiveBounds() throws Exception {
        for (String path : new String[]{GLOBAL, SCOPED}) {
            mvc.perform(get(path).param("vehicleId", VEHICLE.toString())
                    .param("status", "OPEN").param("type", "SERVICE_DUE")
                    .param("severity", "HIGH").param("from", START.toString())
                    .param("to", START.plusSeconds(10).toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2))
                    .andExpect(jsonPath("$.content[0].id").value(id(3).toString()))
                    .andExpect(jsonPath("$.content[1].id").value(id(1).toString()))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.size").value(50));
        }
    }

    @Test
    void supportsOpenTimeBoundsAndEqualEndpoints() throws Exception {
        mvc.perform(get(SCOPED).param("from", START.plusSeconds(10).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get(SCOPED).param("to", START.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get(SCOPED).param("from", START.plusSeconds(10).toString())
                .param("to", START.plusSeconds(10).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void paginatesAscendingTiesAndKeepsFilteredTotalsBeyondLastPage() throws Exception {
        for (int page = 0; page < 2; page++) {
            mvc.perform(get(SCOPED).param("from", START.plusSeconds(10).toString())
                    .param("to", START.plusSeconds(10).toString())
                    .param("sort", "createdAt,asc").param("size", "1")
                    .param("page", Integer.toString(page)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].id").value(id(page + 2).toString()))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.first").value(page == 0))
                    .andExpect(jsonPath("$.last").value(page == 1));
        }
        mvc.perform(get(SCOPED).param("status", "OPEN").param("size", "1")
                .param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void distinguishesUnknownGlobalFilterFromMissingScopedVehicle() throws Exception {
        UUID missing = new UUID(0, 300);
        mvc.perform(get(GLOBAL).param("vehicleId", missing.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
        mvc.perform(get("/api/v1/vehicles/" + missing + "/alerts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VEHICLE_NOT_FOUND"));
        mvc.perform(get(SCOPED).param("status", "OPEN").param("severity", "LOW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void returnsTransitionTimestampsAndCompleteDetailWithoutInternalVersion() throws Exception {
        mvc.perform(get(GLOBAL + "/" + id(1)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(10))
                .andExpect(jsonPath("$.vehicleId").value(VEHICLE.toString()))
                .andExpect(jsonPath("$.sourceMessageId").value(id(1001).toString()))
                .andExpect(jsonPath("$.type").value("SERVICE_DUE"))
                .andExpect(jsonPath("$.severity").value("HIGH"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.description").value("Service due"))
                .andExpect(jsonPath("$.createdAt").value(START.toString()))
                .andExpect(jsonPath("$.acknowledgedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.closedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.version").doesNotExist());
        mvc.perform(get(GLOBAL + "/" + id(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acknowledgedAt").value(START.plusSeconds(10).toString()));
        mvc.perform(get(GLOBAL + "/" + id(5)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedAt").value(START.plusSeconds(20).toString()));
    }

    @Test
    void validatesParametersAndErrorEnvelopeOnBothCollectionRoutes() throws Exception {
        for (String path : new String[]{GLOBAL, SCOPED}) {
            for (String[] invalid : new String[][]{
                    {"to", "not-a-date"}, {"size", "not-a-number"}, {"page", "-1"}}) {
                error(get(path).param(invalid[0], invalid[1]), path, "REQUEST_INVALID");
            }
            error(get(path).param("from", START.plusSeconds(1).toString())
                    .param("to", START.toString()), path, "REQUEST_INVALID_TIME_RANGE");
        }
    }

    @Test
    void rejectsEmptyAndRepeatedScalarFiltersOnBothRoutes() throws Exception {
        for (String path : new String[]{GLOBAL, SCOPED}) {
            for (String name : new String[]{"status", "type", "severity", "from", "to",
                    "page", "size", "sort"}) {
                for (String blank : new String[]{"", "   "}) {
                    error(get(path).param(name, blank), path, "REQUEST_INVALID");
                }
            }
            for (String[] parameter : new String[][]{{"status", "OPEN"}, {"type", "SERVICE_DUE"},
                    {"severity", "HIGH"}, {"from", START.toString()}, {"to", START.toString()},
                    {"page", "0"}, {"size", "50"}, {"sort", "createdAt,desc"}}) {
                error(get(path).param(parameter[0], parameter[1], parameter[1]), path,
                        "REQUEST_INVALID");
            }
        }
        error(get(GLOBAL).param("vehicleId", ""), GLOBAL, "REQUEST_INVALID");
        error(get(GLOBAL).param("vehicleId", VEHICLE.toString(), OTHER.toString()), GLOBAL,
                "REQUEST_INVALID");
    }

    private void error(MockHttpServletRequestBuilder request, String path, String code)
            throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.timestamp").isString())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.error").doesNotExist()).andReturn();
        Instant.parse(JsonPath.read(result.getResponse().getContentAsString(), "$.timestamp"));
    }

    private void vehicle(UUID vehicleId, String status) {
        jdbc.update("""
                INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                    next_service_at_km, created_at) VALUES (?, ?, ?, ?, 15000, 90000, ?)
                """, vehicleId, vehicleId.toString(), vehicleId.toString().substring(30), status,
                Timestamp.from(START));
    }

    private void alert(int number, UUID vehicleId, String status, String severity, Instant time) {
        UUID message = id(1000 + number);
        jdbc.update("""
                INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                    received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                    odometer_km, latitude, longitude)
                VALUES (?, ?, ?, ?, ?, ?, 50, 90, 12, 1000, 41, 12)
                """, message, vehicleId, number, Timestamp.from(time), Timestamp.from(time),
                Timestamp.from(time));
        jdbc.update("""
                INSERT INTO maintenance_alerts (id, vehicle_id, source_message_id, type, severity,
                    description, status, created_at, acknowledged_at, closed_at)
                VALUES (?, ?, ?, 'SERVICE_DUE', ?, 'Service due', ?, ?, ?, ?)
                """, id(number), vehicleId, message, severity, status, Timestamp.from(time),
                status.equals("ACKNOWLEDGED") ? Timestamp.from(time) : null,
                status.equals("CLOSED") ? Timestamp.from(time) : null);
    }

    private static UUID id(int number) {
        return new UUID(0, number);
    }
}
