package it.fleetpulse.api.dashboard;

import io.swagger.v3.oas.annotations.media.Schema;
import it.fleetpulse.api.vehicle.VehicleStatus;

import java.util.List;
import java.util.Map;

public record DashboardResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0") long totalVehicles,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Conteggi per stato, sempre con le chiavi ACTIVE e DISABLED")
    Map<VehicleStatus, Long> vehiclesByStatus,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0",
        description = "Veicoli distinti con observedAt nella finestra inclusiva, " +
            "esclusi timestamp futuri")
    long recentlyReportingVehicles,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0",
        description = "Numero di alert con stato OPEN") long openAlerts,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
        description = "Alert OPEN o ACKNOWLEDGED, ordinati per severity, createdAt DESC e id ASC")
    List<DashboardAlertResponse> relevantAlerts
) {
    public DashboardResponse {
        vehiclesByStatus = Map.copyOf(vehiclesByStatus);
        relevantAlerts = List.copyOf(relevantAlerts);
    }
}
