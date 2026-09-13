package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.PagedResponse;
import it.fleetpulse.api.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MaintenanceAlertServiceTest {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final UUID ALERT_ID =
        UUID.fromString("f2607610-5100-4723-93d0-e6bbdcf00da0");

    private final MaintenanceAlertRepository alerts = mock(MaintenanceAlertRepository.class);
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final MaintenanceAlertMapper mapper = mock(MaintenanceAlertMapper.class);
    private final MaintenanceAlertPageableFactory pageableFactory =
        mock(MaintenanceAlertPageableFactory.class);
    private final MaintenanceAlertRequestValidator validator =
        new MaintenanceAlertRequestValidator();
    private final MaintenanceAlertService service = new MaintenanceAlertService(alerts, vehicles,
        mapper, pageableFactory, validator);

    private final Pageable pageable = Pageable.ofSize(50);

    @BeforeEach
    void configurePageable() {
        when(pageableFactory.create(0, 50, "createdAt,desc")).thenReturn(pageable);
    }

    @Test
    void scopedSearchRejectsMissingVehicleBeforeQueryingAlerts() {
        when(vehicles.existsById(VEHICLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.findByVehicleId(VEHICLE_ID, request()))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND));

        verifyNoInteractions(alerts);
    }

    @Test
    void scopedSearchReturnsMappedPage() {
        MaintenanceAlertEntity entity = mock(MaintenanceAlertEntity.class);
        MaintenanceAlertResponse response = response();
        when(vehicles.existsById(VEHICLE_ID)).thenReturn(true);
        when(alerts.findAll(anySpecification(), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));
        when(mapper.toResponse(entity)).thenReturn(response);

        PagedResponse<MaintenanceAlertResponse> result =
            service.findByVehicleId(VEHICLE_ID, request());

        assertThat(result.content()).containsExactly(response);
        assertThat(result.totalElements()).isEqualTo(1);
    }

    @Test
    void globalSearchDoesNotRequireVehicleExistence() {
        when(alerts.findAll(anySpecification(), eq(pageable)))
            .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        PagedResponse<MaintenanceAlertResponse> result = service.search(VEHICLE_ID, request());

        assertThat(result.content()).isEmpty();
        verify(vehicles, never()).existsById(any());
    }

    @Test
    void returnsAlertDetail() {
        MaintenanceAlertEntity entity = mock(MaintenanceAlertEntity.class);
        MaintenanceAlertResponse response = response();
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(entity));
        when(mapper.toResponse(entity)).thenReturn(response);

        assertThat(service.findById(ALERT_ID)).isEqualTo(response);
    }

    @Test
    void rejectsMissingAlertDetail() {
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(ALERT_ID))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.ALERT_NOT_FOUND));
    }

    @Test
    void rejectsInvertedRangeBeforeRepositoryQuery() {
        Instant from = Instant.parse("2026-08-01T11:00:00Z");
        Instant to = Instant.parse("2026-08-01T10:00:00Z");
        MaintenanceAlertSearchRequest request = new MaintenanceAlertSearchRequest(null, null,
            null, from, to, 0, 50, "createdAt,desc");

        assertThatThrownBy(() -> service.search(null, request))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.REQUEST_INVALID_TIME_RANGE));
        verifyNoInteractions(alerts);
    }

    private MaintenanceAlertSearchRequest request() {
        return new MaintenanceAlertSearchRequest(null, null, null, null, null, 0, 50,
            "createdAt,desc");
    }

    private Specification<MaintenanceAlertEntity> anySpecification() {
        return any();
    }

    private MaintenanceAlertResponse response() {
        return new MaintenanceAlertResponse(ALERT_ID, VEHICLE_ID, UUID.randomUUID(),
            AlertType.SERVICE_DUE, AlertSeverity.MEDIUM, "Service due", AlertStatus.OPEN,
            Instant.parse("2026-08-01T10:16:00Z"), null, null);
    }
}
