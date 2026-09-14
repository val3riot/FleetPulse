package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MaintenanceAlertTransitionAttemptTest {
    private static final UUID ALERT_ID =
        UUID.fromString("f2607610-5100-4723-93d0-e6bbdcf00da0");
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private final MaintenanceAlertRepository alerts = mock(MaintenanceAlertRepository.class);
    private final MaintenanceAlertStateMachine stateMachine =
        mock(MaintenanceAlertStateMachine.class);
    private final MaintenanceAlertMapper mapper = mock(MaintenanceAlertMapper.class);
    private final MaintenanceAlertTransitionAttempt attempt = new MaintenanceAlertTransitionAttempt(
        alerts, stateMachine, mapper, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void persistsAndMapsChangedAlert() {
        MaintenanceAlertEntity alert = mock(MaintenanceAlertEntity.class);
        MaintenanceAlertEntity persisted = mock(MaintenanceAlertEntity.class);
        MaintenanceAlertResponse response = mock(MaintenanceAlertResponse.class);
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(alert));
        when(stateMachine.transition(alert, AlertStatusTarget.ACKNOWLEDGED, NOW)).thenReturn(true);
        when(alerts.saveAndFlush(alert)).thenReturn(persisted);
        when(mapper.toResponse(persisted)).thenReturn(response);

        assertThat(attempt.execute(ALERT_ID, AlertStatusTarget.ACKNOWLEDGED)).isEqualTo(response);

        verify(alerts).saveAndFlush(alert);
    }

    @Test
    void mapsIdempotentAlertWithoutWriting() {
        MaintenanceAlertEntity alert = mock(MaintenanceAlertEntity.class);
        MaintenanceAlertResponse response = mock(MaintenanceAlertResponse.class);
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.of(alert));
        when(stateMachine.transition(alert, AlertStatusTarget.CLOSED, NOW)).thenReturn(false);
        when(mapper.toResponse(alert)).thenReturn(response);

        assertThat(attempt.execute(ALERT_ID, AlertStatusTarget.CLOSED)).isEqualTo(response);

        verify(alerts, never()).saveAndFlush(alert);
    }

    @Test
    void rejectsMissingAlertBeforeApplyingTransition() {
        when(alerts.findById(ALERT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> attempt.execute(ALERT_ID, AlertStatusTarget.CLOSED))
            .isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getErrorCode())
                    .isEqualTo(ErrorCode.ALERT_NOT_FOUND));

        verifyNoInteractions(stateMachine, mapper);
    }
}
