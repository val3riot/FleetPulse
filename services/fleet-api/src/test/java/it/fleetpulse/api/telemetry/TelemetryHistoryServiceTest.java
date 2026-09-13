package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleEntity;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleRepository;
import it.fleetpulse.api.vehicle.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryHistoryServiceTest {
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final Instant FROM = Instant.parse("2026-08-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T11:00:00Z");

    @Mock VehicleRepository vehicles;
    @Mock TelemetrySampleRepository samples;
    @Mock TelemetrySampleMapper mapper;
    @Mock TelemetrySampleEntity entity;

    private TelemetryHistoryPageableFactory pageableFactory;
    private TelemetryHistoryService service;

    @BeforeEach
    void setUp() {
        pageableFactory = new TelemetryHistoryPageableFactory();
        service = new TelemetryHistoryService(vehicles, samples, mapper, pageableFactory,
            new TelemetryHistoryRequestValidator());
    }

    @Test
    void returnsMappedPageMetadata() {
        TelemetryHistoryRequest request = request();
        Pageable pageable = pageableFactory.create(0, 50, "observedAt,desc");
        TelemetrySampleResponse response = response();
        when(vehicles.existsById(VEHICLE_ID)).thenReturn(true);
        when(samples.findAllByVehicleIdAndObservedAtBetween(VEHICLE_ID, FROM, TO, pageable))
            .thenReturn(new PageImpl<>(List.of(entity), pageable, 1));
        when(mapper.toResponse(entity)).thenReturn(response);

        TelemetryHistoryResponse result = service.findByVehicleId(VEHICLE_ID, request);

        assertThat(result.content()).containsExactly(response);
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(50);
        assertThat(result.totalElements()).isEqualTo(1);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
    }

    @Test
    void distinguishesMissingVehicleFromEmptyHistory() {
        when(vehicles.existsById(VEHICLE_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.findByVehicleId(VEHICLE_ID, request()))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.VEHICLE_NOT_FOUND));
        verify(samples, never()).findAllByVehicleIdAndObservedAtBetween(any(), any(), any(), any());
    }

    @Test
    void rejectsInvertedRangeBeforeDatabaseAccess() {
        TelemetryHistoryRequest request =
            new TelemetryHistoryRequest(TO, FROM, 0, 50, "observedAt,desc");

        assertThatThrownBy(() -> service.findByVehicleId(VEHICLE_ID, request))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.REQUEST_INVALID_TIME_RANGE));
        verify(vehicles, never()).existsById(any());
        verify(samples, never()).findAllByVehicleIdAndObservedAtBetween(any(), any(), any(), any());
    }

    private TelemetryHistoryRequest request() {
        return new TelemetryHistoryRequest(FROM, TO, 0, 50, "observedAt,desc");
    }

    private TelemetrySampleResponse response() {
        return new TelemetrySampleResponse(1L, UUID.randomUUID(), VEHICLE_ID, 42, FROM,
            FROM.plusMillis(10), FROM.plusMillis(20), 72.4, 91.8, 12.6, 85312, 41.9, 12.4);
    }
}
