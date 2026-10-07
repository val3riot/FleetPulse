package it.fleetpulse.api.vehicle;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.DatabaseAvailabilityClassifier;
import it.fleetpulse.api.common.DatabaseConstraintErrorResolver;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.TimeConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.webmvc.autoconfigure.WebMvcObservationAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleController.class)
@AutoConfigureMetrics
@ImportAutoConfiguration({PrometheusMetricsExportAutoConfiguration.class,
    WebMvcObservationAutoConfiguration.class})
@Import({VehiclePageableFactory.class, TimeConfiguration.class,
    DatabaseConstraintErrorResolver.class, DatabaseAvailabilityClassifier.class})
class HttpRequestMetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrometheusMeterRegistry registry;

    @MockitoBean
    private VehicleService service;

    @Test
    void exportsOneRouteForDifferentVehicleIdsAndExcludesRequestData() throws Exception {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<UUID> requestIds = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (int index = 0; index < ids.size(); index++) {
            UUID id = ids.get(index);
            if (index == 2) {
                when(service.findById(id)).thenThrow(new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));
            } else {
                when(service.findById(id)).thenReturn(new VehicleResponse(id, "VAN-METRICS", "FP041AA",
                    VehicleStatus.ACTIVE, 15_000, 90_000, Instant.parse("2026-10-07T12:00:00Z")));
            }
            mockMvc.perform(get("/api/v1/vehicles/{vehicleId}", id)
                    .queryParam("private", "private-query-marker")
                    .header("X-Request-ID", requestIds.get(index)))
                .andExpect(index == 2 ? status().isNotFound() : status().isOk());
        }

        var timers = registry.find("http.server.requests").timers();
        assertThat(timers).hasSize(2);
        assertThat(timers).allSatisfy(timer ->
            assertThat(timer.getId().getTag("uri")).isEqualTo("/api/v1/vehicles/{vehicleId}"));
        assertThat(registry.get("http.server.requests").tag("status", "200").timer().count()).isEqualTo(2);
        assertThat(registry.get("http.server.requests").tag("status", "404").timer().count()).isEqualTo(1);

        String export = registry.scrape();
        assertThat(export).contains("http_server_requests_seconds_count{",
            "http_server_requests_seconds_sum{", "http_server_requests_seconds_bucket{",
            "uri=\"/api/v1/vehicles/{vehicleId}\"", "le=\"2.0\"", "le=\"+Inf\"")
            .doesNotContain("private-query-marker", "VAN-METRICS", "FP041AA", "vehicleId=", "requestId=");
        ids.forEach(id -> assertThat(export).doesNotContain(id.toString()));
        requestIds.forEach(id -> assertThat(export).doesNotContain(id.toString()));
    }
}
