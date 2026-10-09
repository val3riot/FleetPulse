package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaintenanceAlertStateMachineTest {
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private final MaintenanceAlertStateMachine stateMachine = new MaintenanceAlertStateMachine();

    @Test
    void acknowledgesOpenAlert() {
        MaintenanceAlertEntity alert = alertWithStatus(AlertStatus.OPEN);

        assertThat(stateMachine.transition(alert, AlertStatusTarget.ACKNOWLEDGED, NOW)).isTrue();

        verify(alert).acknowledge(NOW);
        verify(alert, never()).close(NOW);
    }

    @Test
    void closesOpenAlert() {
        MaintenanceAlertEntity alert = alertWithStatus(AlertStatus.OPEN);

        assertThat(stateMachine.transition(alert, AlertStatusTarget.CLOSED, NOW)).isTrue();

        verify(alert).close(NOW);
    }

    @Test
    void closesAcknowledgedAlert() {
        MaintenanceAlertEntity alert = alertWithStatus(AlertStatus.ACKNOWLEDGED);

        assertThat(stateMachine.transition(alert, AlertStatusTarget.CLOSED, NOW)).isTrue();

        verify(alert).close(NOW);
    }

    @Test
    void treatsCurrentTargetAsIdempotent() {
        MaintenanceAlertEntity acknowledged = alertWithStatus(AlertStatus.ACKNOWLEDGED);
        MaintenanceAlertEntity closed = alertWithStatus(AlertStatus.CLOSED);

        assertThat(stateMachine.transition(acknowledged, AlertStatusTarget.ACKNOWLEDGED, NOW))
                .isFalse();
        assertThat(stateMachine.transition(closed, AlertStatusTarget.CLOSED, NOW)).isFalse();

        verify(acknowledged, never()).acknowledge(NOW);
        verify(acknowledged, never()).close(NOW);
        verify(closed, never()).acknowledge(NOW);
        verify(closed, never()).close(NOW);
    }

    @Test
    void rejectsAcknowledgeAfterClose() {
        MaintenanceAlertEntity alert = alertWithStatus(AlertStatus.CLOSED);

        assertThatThrownBy(
                () -> stateMachine.transition(alert, AlertStatusTarget.ACKNOWLEDGED, NOW))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT));
    }

    private MaintenanceAlertEntity alertWithStatus(AlertStatus status) {
        MaintenanceAlertEntity alert = mock(MaintenanceAlertEntity.class);
        when(alert.getStatus()).thenReturn(status);
        return alert;
    }
}
