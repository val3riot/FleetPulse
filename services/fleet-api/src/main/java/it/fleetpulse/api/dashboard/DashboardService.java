package it.fleetpulse.api.dashboard;

import it.fleetpulse.api.alert.AlertStatus;
import it.fleetpulse.api.alert.MaintenanceAlertRepository;
import it.fleetpulse.api.alert.MaintenanceAlertSummary;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleRepository;
import it.fleetpulse.api.vehicle.VehicleRepository;
import it.fleetpulse.api.vehicle.VehicleStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {
    private final VehicleRepository vehicles;
    private final TelemetrySampleRepository samples;
    private final MaintenanceAlertRepository alerts;
    private final DashboardProperties properties;
    private final Clock clock;

    public DashboardService(VehicleRepository vehicles, TelemetrySampleRepository samples,
            MaintenanceAlertRepository alerts, DashboardProperties properties, Clock clock) {
        this.vehicles = vehicles;
        this.samples = samples;
        this.alerts = alerts;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Quattro SELECT JPA nello stesso snapshot, senza entity o count di pagina aggiuntivi.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardResponse getDashboard() {
        Instant now = clock.instant();
        Map<VehicleStatus, Long> counts = new EnumMap<>(VehicleStatus.class);
        for (VehicleStatus status : VehicleStatus.values()) {
            counts.put(status, 0L);
        }
        vehicles.countVehiclesByStatus()
                .forEach(count -> counts.put(count.status(), count.total()));
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long recentlyReporting = samples.countDistinctReportingVehicles(
                now.minus(properties.reportingWindow()), now);
        long openAlerts = alerts.countByStatus(AlertStatus.OPEN);
        List<DashboardAlertResponse> relevantAlerts = alerts.findRelevantAlerts(
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED),
                PageRequest.of(0, properties.relevantAlertsLimit()))
                .stream().map(this::toResponse).toList();
        return new DashboardResponse(total, counts, recentlyReporting, openAlerts, relevantAlerts);
    }

    private DashboardAlertResponse toResponse(MaintenanceAlertSummary summary) {
        return new DashboardAlertResponse(summary.id(), summary.vehicleId(), summary.type(),
                summary.severity(), summary.status(), summary.description(), summary.createdAt());
    }
}
