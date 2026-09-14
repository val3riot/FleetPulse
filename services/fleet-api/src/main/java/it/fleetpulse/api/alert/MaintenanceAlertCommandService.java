package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MaintenanceAlertCommandService {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceAlertCommandService.class);
    private static final int MAX_ATTEMPTS = 2;

    private final MaintenanceAlertTransitionAttempt transitionAttempt;

    public MaintenanceAlertCommandService(MaintenanceAlertTransitionAttempt transitionAttempt) {
        this.transitionAttempt = transitionAttempt;
    }

    public MaintenanceAlertResponse changeStatus(UUID alertId, ChangeAlertStatusRequest request) {
        for (int attemptNumber = 1; attemptNumber <= MAX_ATTEMPTS; attemptNumber++) {
            try {
                MaintenanceAlertResponse response =
                    transitionAttempt.execute(alertId, request.status());
                log.info("Maintenance alert status command completed: alertId={}, status={}, " +
                        "attempt={}", alertId, response.status(), attemptNumber);
                return response;
            } catch (OptimisticLockingFailureException exception) {
                log.debug("Concurrent maintenance alert update detected: alertId={}, target={}, " +
                    "attempt={}", alertId, request.status(), attemptNumber);
                if (attemptNumber == MAX_ATTEMPTS) {
                    throw new ApplicationException(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT);
                }
            }
        }

        throw new IllegalStateException("Maintenance alert transition attempts exhausted");
    }
}
