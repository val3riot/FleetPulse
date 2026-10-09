package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaintenanceAlertCommandServiceTest {
    private static final UUID ALERT_ID = UUID.fromString("f2607610-5100-4723-93d0-e6bbdcf00da0");
    private final MaintenanceAlertTransitionAttempt transitionAttempt = mock(
            MaintenanceAlertTransitionAttempt.class);
    private final MaintenanceAlertCommandService service = new MaintenanceAlertCommandService(
            transitionAttempt);

    @Test
    void returnsFirstSuccessfulAttempt() {
        MaintenanceAlertResponse response = response(AlertStatus.ACKNOWLEDGED);
        when(transitionAttempt.execute(ALERT_ID, AlertStatusTarget.ACKNOWLEDGED))
                .thenReturn(response);

        assertThat(service.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.ACKNOWLEDGED))).isEqualTo(response);

        verify(transitionAttempt).execute(ALERT_ID, AlertStatusTarget.ACKNOWLEDGED);
    }

    @Test
    void reEvaluatesOnceAfterOptimisticConflict() {
        MaintenanceAlertResponse response = response(AlertStatus.CLOSED);
        when(transitionAttempt.execute(ALERT_ID, AlertStatusTarget.CLOSED))
                .thenThrow(new OptimisticLockingFailureException("concurrent update"))
                .thenReturn(response);

        assertThat(service.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.CLOSED))).isEqualTo(response);

        verify(transitionAttempt, times(2)).execute(ALERT_ID, AlertStatusTarget.CLOSED);
    }

    @Test
    void mapsSecondOptimisticConflictWithoutFurtherRetries() {
        when(transitionAttempt.execute(ALERT_ID, AlertStatusTarget.CLOSED))
                .thenThrow(new OptimisticLockingFailureException("first"))
                .thenThrow(new OptimisticLockingFailureException("second"));

        assertThatThrownBy(() -> service.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.CLOSED)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT));

        verify(transitionAttempt, times(2)).execute(ALERT_ID, AlertStatusTarget.CLOSED);
    }

    @Test
    void propagatesDomainConflictFoundDuringReEvaluation() {
        when(transitionAttempt.execute(ALERT_ID, AlertStatusTarget.ACKNOWLEDGED))
                .thenThrow(new OptimisticLockingFailureException("concurrent close"))
                .thenThrow(new ApplicationException(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT));

        assertThatThrownBy(() -> service.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.ACKNOWLEDGED)))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT));

        verify(transitionAttempt, times(2)).execute(ALERT_ID, AlertStatusTarget.ACKNOWLEDGED);
    }

    @Test
    void doesNotRetryDomainConflict() {
        when(transitionAttempt.execute(ALERT_ID, AlertStatusTarget.ACKNOWLEDGED))
                .thenThrow(new ApplicationException(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT));

        assertThatThrownBy(() -> service.changeStatus(ALERT_ID,
                new ChangeAlertStatusRequest(AlertStatusTarget.ACKNOWLEDGED)))
                .isInstanceOf(ApplicationException.class);

        verify(transitionAttempt).execute(ALERT_ID, AlertStatusTarget.ACKNOWLEDGED);
    }

    private MaintenanceAlertResponse response(AlertStatus status) {
        return new MaintenanceAlertResponse(ALERT_ID, UUID.randomUUID(), UUID.randomUUID(),
                AlertType.SERVICE_DUE, AlertSeverity.MEDIUM, "Service due", status,
                Instant.parse("2026-08-01T10:16:00Z"), null, null);
    }
}
