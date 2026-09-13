package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.DatabaseAvailabilityClassifier;
import it.fleetpulse.api.common.DatabaseConstraintErrorResolver;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.GlobalExceptionHandler;
import it.fleetpulse.api.common.PagedResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MaintenanceAlertController.class)
@Import({GlobalExceptionHandler.class, DatabaseConstraintErrorResolver.class,
    DatabaseAvailabilityClassifier.class, MaintenanceAlertControllerTest.FixedClock.class})
class MaintenanceAlertControllerTest {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID ALERT_ID =
        UUID.fromString("f2607610-5100-4723-93d0-e6bbdcf00da0");
    private static final Instant FROM = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T11:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MaintenanceAlertService service;

    @Test
    void returnsGlobalFilteredPage() throws Exception {
        MaintenanceAlertSearchRequest request = new MaintenanceAlertSearchRequest(
            AlertStatus.OPEN, AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH,
            FROM, TO, 1, 20, "createdAt,asc");
        when(service.search(VEHICLE_ID, request)).thenReturn(page());

        mockMvc.perform(get("/api/v1/alerts")
                .param("vehicleId", VEHICLE_ID.toString())
                .param("status", "OPEN")
                .param("type", "ENGINE_TEMPERATURE_HIGH")
                .param("severity", "HIGH")
                .param("from", FROM.toString())
                .param("to", TO.toString())
                .param("page", "1")
                .param("size", "20")
                .param("sort", "createdAt,asc"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.content[0].id").value(ALERT_ID.toString()))
            .andExpect(jsonPath("$.content[0].vehicleId").value(VEHICLE_ID.toString()))
            .andExpect(jsonPath("$.content[0].type").value("ENGINE_TEMPERATURE_HIGH"))
            .andExpect(jsonPath("$.content[0].severity").value("HIGH"))
            .andExpect(jsonPath("$.content[0].status").value("OPEN"))
            .andExpect(jsonPath("$.content[0].acknowledgedAt").isEmpty())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.totalElements").value(1));

        verify(service).search(VEHICLE_ID, request);
    }

    @Test
    void appliesDefaultsToScopedCollection() throws Exception {
        MaintenanceAlertSearchRequest request = request();
        when(service.findByVehicleId(VEHICLE_ID, request)).thenReturn(page());

        mockMvc.perform(get("/api/v1/vehicles/{vehicleId}/alerts", VEHICLE_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));

        verify(service).findByVehicleId(VEHICLE_ID, request);
    }

    @Test
    void returnsAlertDetail() throws Exception {
        when(service.findById(ALERT_ID)).thenReturn(response());

        mockMvc.perform(get("/api/v1/alerts/{alertId}", ALERT_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(ALERT_ID.toString()))
            .andExpect(jsonPath("$.sourceMessageId")
                .value("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"))
            .andExpect(jsonPath("$.createdAt").value("2026-08-01T10:16:00Z"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "status|INVALID", "type|INVALID", "severity|INVALID", "from|not-an-instant",
        "page|-1", "size|0", "size|101", "sort|status,desc", "sort|createdAt,up"
    })
    void rejectsInvalidCollectionParameters(String name, String value) throws Exception {
        expectError(mockMvc.perform(get("/api/v1/alerts").param(name, value)), 400,
            ErrorCode.REQUEST_INVALID);
    }

    @Test
    void rejectsInvalidVehicleAndAlertUuids() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/alerts").param("vehicleId", "invalid")),
            400, ErrorCode.REQUEST_INVALID);
        expectError(mockMvc.perform(get("/api/v1/vehicles/invalid/alerts")),
            400, ErrorCode.REQUEST_INVALID);
        expectError(mockMvc.perform(get("/api/v1/alerts/invalid")),
            400, ErrorCode.REQUEST_INVALID);
    }

    @Test
    void mapsInvertedRangeToDedicatedError() throws Exception {
        MaintenanceAlertSearchRequest request = new MaintenanceAlertSearchRequest(null, null,
            null, TO, FROM, 0, 50, "createdAt,desc");
        when(service.search(null, request))
            .thenThrow(new ApplicationException(ErrorCode.REQUEST_INVALID_TIME_RANGE));

        expectError(mockMvc.perform(get("/api/v1/alerts")
                .param("from", TO.toString()).param("to", FROM.toString())),
            400, ErrorCode.REQUEST_INVALID_TIME_RANGE);
    }

    @Test
    void mapsMissingScopedVehicleAndAlert() throws Exception {
        when(service.findByVehicleId(VEHICLE_ID, request()))
            .thenThrow(new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));
        when(service.findById(ALERT_ID))
            .thenThrow(new ApplicationException(ErrorCode.ALERT_NOT_FOUND));

        expectError(mockMvc.perform(get("/api/v1/vehicles/{vehicleId}/alerts", VEHICLE_ID)),
            404, ErrorCode.VEHICLE_NOT_FOUND);
        expectError(mockMvc.perform(get("/api/v1/alerts/{alertId}", ALERT_ID)),
            404, ErrorCode.ALERT_NOT_FOUND);
    }

    @Test
    void mapsDatabaseConnectionFailureToServiceUnavailable() throws Exception {
        when(service.search(null, request())).thenThrow(
            new DataAccessResourceFailureException("database unavailable",
                new SQLException("connection refused", "08006")));

        expectError(mockMvc.perform(get("/api/v1/alerts")), 503,
            ErrorCode.SERVICE_UNAVAILABLE);
    }

    private MaintenanceAlertSearchRequest request() {
        return new MaintenanceAlertSearchRequest(null, null, null, null, null, 0, 50,
            "createdAt,desc");
    }

    private PagedResponse<MaintenanceAlertResponse> page() {
        return new PagedResponse<>(List.of(response()), 0, 50, 1, 1, true, true);
    }

    private MaintenanceAlertResponse response() {
        return new MaintenanceAlertResponse(ALERT_ID, VEHICLE_ID,
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            AlertType.ENGINE_TEMPERATURE_HIGH, AlertSeverity.HIGH,
            "Temperatura motore oltre soglia", AlertStatus.OPEN,
            Instant.parse("2026-08-01T10:16:00Z"), null, null);
    }

    private ResultActions expectError(ResultActions result, int statusCode, ErrorCode code)
        throws Exception {
        return result.andExpect(status().is(statusCode))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
            .andExpect(jsonPath("$.status").value(statusCode))
            .andExpect(jsonPath("$.code").value(code.getCode()))
            .andExpect(jsonPath("$.message").value(code.getDefaultMessage()));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
