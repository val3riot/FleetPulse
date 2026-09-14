package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class MaintenanceAlertTransitionAttempt {
    private final MaintenanceAlertRepository alerts;
    private final MaintenanceAlertStateMachine stateMachine;
    private final MaintenanceAlertMapper mapper;
    private final Clock clock;

    public MaintenanceAlertTransitionAttempt(MaintenanceAlertRepository alerts,
        MaintenanceAlertStateMachine stateMachine, MaintenanceAlertMapper mapper, Clock clock) {
        this.alerts = alerts;
        this.stateMachine = stateMachine;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MaintenanceAlertResponse execute(UUID alertId, AlertStatusTarget target) {
        MaintenanceAlertEntity alert = alerts.findById(alertId)
            .orElseThrow(() -> new ApplicationException(ErrorCode.ALERT_NOT_FOUND));
        Instant transitionedAt = clock.instant();
        boolean changed = stateMachine.transition(alert, target, transitionedAt);
        if (changed) {
            alert = alerts.saveAndFlush(alert);
        }
        return mapper.toResponse(alert);
    }
}
