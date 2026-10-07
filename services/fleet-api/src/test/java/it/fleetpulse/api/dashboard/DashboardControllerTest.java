package it.fleetpulse.api.dashboard;

import it.fleetpulse.api.common.DatabaseAvailabilityClassifier;
import it.fleetpulse.api.common.DatabaseConstraintErrorResolver;
import it.fleetpulse.api.common.GlobalExceptionHandler;
import it.fleetpulse.api.vehicle.VehicleStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Import({GlobalExceptionHandler.class, DatabaseConstraintErrorResolver.class,
    DatabaseAvailabilityClassifier.class, it.fleetpulse.api.common.TimeConfiguration.class})
class DashboardControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private DashboardService service;

    @Test
    void exposesEmptyDashboardContract() throws Exception {
        when(service.getDashboard()).thenReturn(new DashboardResponse(0,
            Map.of(VehicleStatus.ACTIVE, 0L, VehicleStatus.DISABLED, 0L), 0, 0, List.of()));
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isOk())
            .andExpect(jsonPath("$.totalVehicles").value(0))
            .andExpect(jsonPath("$.vehiclesByStatus.ACTIVE").value(0))
            .andExpect(jsonPath("$.vehiclesByStatus.DISABLED").value(0))
            .andExpect(jsonPath("$.recentlyReportingVehicles").value(0))
            .andExpect(jsonPath("$.openAlerts").value(0))
            .andExpect(jsonPath("$.relevantAlerts").isEmpty());
    }

    @Test
    void mapsDatabaseFailureWithoutPartialDashboardOrLeakage() throws Exception {
        when(service.getDashboard())
            .thenThrow(new DataAccessResourceFailureException("private details"));
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(jsonPath("$.path").value("/api/v1/dashboard"))
            .andExpect(jsonPath("$.details").isEmpty())
            .andExpect(jsonPath("$.totalVehicles").doesNotExist());
    }

    @Test
    void mapsUnexpectedFailureToInternalError() throws Exception {
        when(service.getDashboard()).thenThrow(new IllegalStateException("private details"));
        mvc.perform(get("/api/v1/dashboard")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.details").isEmpty())
            .andExpect(jsonPath("$.totalVehicles").doesNotExist());
    }
}
