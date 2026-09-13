package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.DatabaseAvailabilityClassifier;
import it.fleetpulse.api.common.DatabaseConstraintErrorResolver;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelemetryHistoryController.class)
@Import({GlobalExceptionHandler.class, DatabaseConstraintErrorResolver.class,
    DatabaseAvailabilityClassifier.class, TelemetryHistoryControllerTest.FixedClock.class})
class TelemetryHistoryControllerTest {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final Instant FROM = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T11:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-02T08:00:00Z");
    private static final String PATH = "/api/v1/vehicles/" + VEHICLE_ID + "/telemetry";

    @Autowired MockMvc mockMvc;
    @MockitoBean TelemetryHistoryService service;

    @Test
    void returnsTheDocumentedHistoryContractAndAppliesDefaults() throws Exception {
        TelemetryHistoryRequest request = new TelemetryHistoryRequest(FROM, TO, null, null, null);
        when(service.findByVehicleId(VEHICLE_ID, request)).thenReturn(response());

        mockMvc.perform(get(PATH).param("from", FROM.toString()).param("to", TO.toString()))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(1254))
            .andExpect(jsonPath("$.content[0].messageId")
                .value("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"))
            .andExpect(jsonPath("$.content[0].vehicleId").value(VEHICLE_ID.toString()))
            .andExpect(jsonPath("$.content[0].observedAt").value(FROM.toString()))
            .andExpect(jsonPath("$.page").value(0)).andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.totalPages").value(1))
            .andExpect(jsonPath("$.first").value(true)).andExpect(jsonPath("$.last").value(true));

        verify(service).findByVehicleId(VEHICLE_ID, request);
    }

    @Test
    void rejectsMissingRequiredRange() throws Exception {
        expectError(mockMvc.perform(get(PATH)), 400, ErrorCode.REQUEST_INVALID)
            .andExpect(jsonPath("$.details[*].field", hasItem("from")))
            .andExpect(jsonPath("$.details[*].field", hasItem("to")));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void rejectsInvalidBindingAndValidatedParameters(String name, String value, String field)
        throws Exception {
        MockHttpServletRequestBuilder request = get(PATH).param("to", TO.toString());
        if (!"from".equals(name)) {
            request.param("from", FROM.toString());
        }
        request.param(name, value);
        ResultActions result = mockMvc.perform(request);

        expectError(result, 400, ErrorCode.REQUEST_INVALID)
            .andExpect(jsonPath("$.details[*].field", hasItem(field)));
    }

    @Test
    void rejectsInvalidUuid() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/vehicles/not-a-uuid/telemetry")
                .param("from", FROM.toString()).param("to", TO.toString())), 400,
            ErrorCode.REQUEST_INVALID).andExpect(jsonPath("$.details[0].field").value("vehicleId"));
    }

    @Test
    void mapsInvertedRangeToDedicatedError() throws Exception {
        TelemetryHistoryRequest request =
            new TelemetryHistoryRequest(TO, FROM, 0, 50, "observedAt,desc");
        when(service.findByVehicleId(VEHICLE_ID, request))
            .thenThrow(new ApplicationException(ErrorCode.REQUEST_INVALID_TIME_RANGE));

        expectError(mockMvc.perform(get(PATH).param("from", TO.toString())
            .param("to", FROM.toString())), 400, ErrorCode.REQUEST_INVALID_TIME_RANGE);
    }

    @Test
    void mapsMissingVehicle() throws Exception {
        when(service.findByVehicleId(VEHICLE_ID, request()))
            .thenThrow(new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));

        expectError(validRequest(), 404, ErrorCode.VEHICLE_NOT_FOUND);
    }

    @Test
    void mapsDatabaseConnectionFailureToServiceUnavailable() throws Exception {
        when(service.findByVehicleId(VEHICLE_ID, request())).thenThrow(
            new DataAccessResourceFailureException("database unavailable",
                new SQLException("connection refused", "08006")));

        expectError(validRequest(), 503, ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void mapsOtherDatabaseFailuresToInternalErrorWithoutLeakingDetails() throws Exception {
        when(service.findByVehicleId(VEHICLE_ID, request()))
            .thenThrow(new DataRetrievalFailureException("secret query detail"));

        expectError(validRequest(), 500, ErrorCode.INTERNAL_ERROR)
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("secret query detail"))));
    }

    private ResultActions validRequest() throws Exception {
        return mockMvc.perform(
            get(PATH).param("from", FROM.toString()).param("to", TO.toString()));
    }

    private TelemetryHistoryRequest request() {
        return new TelemetryHistoryRequest(FROM, TO, 0, 50, "observedAt,desc");
    }

    private TelemetryHistoryResponse response() {
        TelemetrySampleResponse sample = new TelemetrySampleResponse(1254L,
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"), VEHICLE_ID, 42, FROM,
            FROM.plusMillis(83), FROM.plusMillis(150), 72.4, 91.8, 12.6, 85312, 41.9028,
            12.4964);
        return new TelemetryHistoryResponse(List.of(sample), 0, 50, 1, 1, true, true);
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

    static Stream<Arguments> invalidParameters() {
        return Stream.of(Arguments.of("from", "not-an-instant", "from"),
            Arguments.of("page", "-1", "page"), Arguments.of("size", "0", "size"),
            Arguments.of("size", "101", "size"),
            Arguments.of("sort", "sequenceNumber,desc", "sort"),
            Arguments.of("sort", "observedAt,up", "sort"));
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
