package it.fleetpulse.api.dashboard;

import it.fleetpulse.api.alert.AlertSeverity;
import it.fleetpulse.api.alert.AlertStatus;
import it.fleetpulse.api.alert.AlertType;
import it.fleetpulse.api.alert.MaintenanceAlertRepository;
import it.fleetpulse.api.alert.MaintenanceAlertSummary;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleRepository;
import it.fleetpulse.api.vehicle.VehicleRepository;
import it.fleetpulse.api.vehicle.VehicleStatus;
import it.fleetpulse.api.vehicle.VehicleStatusCount;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final TelemetrySampleRepository samples = mock(TelemetrySampleRepository.class);
    private final MaintenanceAlertRepository alerts = mock(MaintenanceAlertRepository.class);
    private final Clock clock = spy(Clock.fixed(NOW, ZoneOffset.UTC));
    private final DashboardService service = new DashboardService(vehicles, samples, alerts,
            new DashboardProperties(Duration.ofMinutes(1), 10), clock);

    @Test
    void usesSingleClockReadingAndLongCountsAndPreservesBoundedProjectionOrder() {
        MaintenanceAlertSummary alert = new MaintenanceAlertSummary(UUID.randomUUID(),
                UUID.randomUUID(),
                AlertType.SERVICE_DUE, AlertSeverity.CRITICAL, AlertStatus.ACKNOWLEDGED,
                "Service due", NOW);
        when(vehicles.countVehiclesByStatus())
                .thenReturn(List.of(new VehicleStatusCount(VehicleStatus.ACTIVE, 3000000000L)));
        when(samples.countDistinctReportingVehicles(NOW.minusSeconds(60), NOW)).thenReturn(2L);
        when(alerts.countByStatus(AlertStatus.OPEN)).thenReturn(5L);
        when(alerts.findRelevantAlerts(
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED), PageRequest.of(0, 10)))
                .thenReturn(List.of(alert));

        DashboardResponse response = service.getDashboard();

        assertThat(response.totalVehicles()).isEqualTo(3000000000L);
        assertThat(response.vehiclesByStatus()).containsEntry(VehicleStatus.ACTIVE, 3000000000L)
                .containsEntry(VehicleStatus.DISABLED, 0L);
        assertThat(response.recentlyReportingVehicles()).isEqualTo(2);
        assertThat(response.openAlerts()).isEqualTo(5);
        assertThat(response.relevantAlerts()).containsExactly(new DashboardAlertResponse(
                alert.id(), alert.vehicleId(), alert.type(), alert.severity(), alert.status(),
                alert.description(), alert.createdAt()));
        verify(clock).instant();
        verify(samples).countDistinctReportingVehicles(NOW.minusSeconds(60), NOW);
        verify(alerts).findRelevantAlerts(
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED), PageRequest.of(0, 10));
    }

    @Test
    void usesConfiguredReportingWindowAndAlertLimitInsteadOfDefaults() {
        DashboardService configured = new DashboardService(vehicles, samples, alerts,
                new DashboardProperties(Duration.ofMinutes(5), 7), clock);
        when(vehicles.countVehiclesByStatus())
                .thenReturn(List.of(new VehicleStatusCount(VehicleStatus.ACTIVE, 4L)));
        when(samples.countDistinctReportingVehicles(NOW.minusSeconds(300), NOW)).thenReturn(4L);
        when(alerts.findRelevantAlerts(
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED), PageRequest.of(0, 7)))
                .thenReturn(List.of());

        DashboardResponse response = configured.getDashboard();

        assertThat(response.recentlyReportingVehicles()).isEqualTo(4);
        verify(samples).countDistinctReportingVehicles(NOW.minusSeconds(300), NOW);
        verify(alerts).findRelevantAlerts(
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED), PageRequest.of(0, 7));
    }

    @Test
    void returnsZeroCountsAndEmptyListForEmptyDatabase() {
        when(vehicles.countVehiclesByStatus()).thenReturn(List.of());
        when(alerts.findRelevantAlerts(
                List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED), PageRequest.of(0, 10)))
                .thenReturn(List.of());
        DashboardResponse response = service.getDashboard();
        assertThat(response.totalVehicles()).isZero();
        assertThat(response.vehiclesByStatus()).containsExactlyInAnyOrderEntriesOf(
                Map.of(VehicleStatus.ACTIVE, 0L, VehicleStatus.DISABLED, 0L));
        assertThat(response.relevantAlerts()).isEmpty();
    }

    @Test
    void propagatesFailureInsteadOfReturningPartialCounts() {
        when(vehicles.countVehiclesByStatus()).thenReturn(List.of());
        when(alerts.countByStatus(AlertStatus.OPEN))
                .thenThrow(new IllegalStateException("query failed"));
        assertThatThrownBy(service::getDashboard).isInstanceOf(IllegalStateException.class);
        verify(alerts, never()).findRelevantAlerts(anyCollection(), any(Pageable.class));
    }
}
