package it.fleetpulse.api.state;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.vehicle.VehicleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VehicleStateServiceTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-08T10:00:00Z");
    private final VehicleRepository vehicles = mock(VehicleRepository.class);
    private final LatestStateQuery cache = mock(LatestStateQuery.class);
    private final LatestSampleQuery samples = mock(LatestSampleQuery.class);
    private final LatestStateProjection repair = mock(LatestStateProjection.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final VehicleStateService service = new VehicleStateService(vehicles, cache, samples,
        repair, new VehicleStateProperties(Duration.ofMinutes(1)),
        Clock.fixed(NOW, ZoneOffset.UTC), new VehicleStateObservability(metrics));

    @BeforeEach
    void vehicleExists() {
        when(vehicles.existsById(ID)).thenReturn(true);
    }

    @Test
    void cacheHitAvoidsSampleQueryAndRepair() {
        LatestVehicleState state = state(NOW);
        when(cache.findByVehicleId(ID)).thenReturn(Optional.of(state));
        assertThat(service.findByVehicleId(ID)).isEqualTo(VehicleStateResponse.from(state, false));
        verifyNoInteractions(vehicles, samples, repair);
        assertThat(count("hits")).isEqualTo(1);
        assertThat(count("fallback")).isZero();
    }

    @Test
    void missFallsBackAndRepairsWithoutChangingObservationTime() {
        LatestVehicleState state = state(NOW.minusSeconds(300));
        when(cache.findByVehicleId(ID)).thenReturn(Optional.empty());
        when(samples.findByVehicleId(ID)).thenReturn(Optional.of(state));
        assertThat(service.findByVehicleId(ID)).isEqualTo(VehicleStateResponse.from(state, true));
        verify(repair).updateIfNewer(state);
        var order = inOrder(cache, vehicles, samples);
        order.verify(cache).findByVehicleId(ID);
        order.verify(vehicles).existsById(ID);
        order.verify(samples).findByVehicleId(ID);
        assertThat(count("misses")).isEqualTo(1);
        assertThat(count("fallback")).isEqualTo(1);
        assertThat(count("failures")).isZero();
    }

    @Test
    void redisFailureAndFailedRepairStillReturnPostgresState() {
        LatestVehicleState state = state(NOW);
        when(cache.findByVehicleId(ID)).thenThrow(new LatestStateProjectionException("down"));
        when(samples.findByVehicleId(ID)).thenReturn(Optional.of(state));
        when(repair.updateIfNewer(state)).thenThrow(new LatestStateProjectionException("down"));
        assertThat(service.findByVehicleId(ID)).isEqualTo(VehicleStateResponse.from(state, false));
        assertThat(count("failures")).isEqualTo(1);
        assertThat(count("misses")).isZero();
        assertThat(count("repair.failures")).isEqualTo(1);
        assertThat(count("fallback")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,false", "59999999999,false", "60000000000,false",
        "60000000001,true"})
    void freshnessHasExactBoundaryAndDoesNotCorrectFutureTime(long ageNanos, boolean stale) {
        when(cache.findByVehicleId(ID)).thenReturn(Optional.of(state(NOW.minusNanos(ageNanos))));
        assertThat(service.findByVehicleId(ID).stale()).isEqualTo(stale);
    }

    @Test
    void missingVehicleOnCacheMissDoesNotConsultHistory() {
        when(vehicles.existsById(ID)).thenReturn(false);
        assertThatThrownBy(() -> service.findByVehicleId(ID))
            .isInstanceOfSatisfying(ApplicationException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_NOT_FOUND));
        verify(cache).findByVehicleId(ID);
        verifyNoInteractions(samples, repair);
    }

    @Test
    void missingTelemetryHasDedicatedError() {
        when(cache.findByVehicleId(ID)).thenReturn(Optional.empty());
        when(samples.findByVehicleId(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findByVehicleId(ID))
            .isInstanceOfSatisfying(ApplicationException.class, error ->
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.VEHICLE_STATE_NOT_AVAILABLE));
        verifyNoInteractions(repair);
    }

    @Test
    void postgresFailureIsNotHiddenOrRepaired() {
        when(cache.findByVehicleId(ID)).thenThrow(new LatestStateProjectionException("down"));
        when(samples.findByVehicleId(ID)).thenThrow(new DataAccessResourceFailureException("down"));
        assertThatThrownBy(() -> service.findByVehicleId(ID))
            .isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(repair);
    }

    @Test
    void vehicleVerificationFailureOnCacheMissPropagates() {
        when(vehicles.existsById(ID)).thenThrow(new DataAccessResourceFailureException("down"));
        assertThatThrownBy(() -> service.findByVehicleId(ID))
            .isInstanceOf(DataAccessResourceFailureException.class);
        verify(cache).findByVehicleId(ID);
        verifyNoInteractions(samples, repair);
    }

    private double count(String name) {
        return metrics.get("fleetpulse.api.cache." + name).counter().count();
    }

    private LatestVehicleState state(Instant observedAt) {
        return new LatestVehicleState(ID, 42, observedAt, 72.4, 91.8, 12.6, 85312, 41.9, 12.4);
    }
}
