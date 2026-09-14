package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

@Component
public class MaintenanceAlertStateMachine {

    public boolean transition(MaintenanceAlertEntity alert, AlertStatusTarget target,
        Instant transitionedAt) {
        Objects.requireNonNull(alert);
        Objects.requireNonNull(target);
        Objects.requireNonNull(transitionedAt);

        AlertStatus current = alert.getStatus();
        AlertStatus requested = AlertStatus.valueOf(target.name());
        if (current == requested) {
            return false;
        }

        if (current == AlertStatus.OPEN && target == AlertStatusTarget.ACKNOWLEDGED) {
            alert.acknowledge(transitionedAt);
            return true;
        }
        if ((current == AlertStatus.OPEN || current == AlertStatus.ACKNOWLEDGED)
            && target == AlertStatusTarget.CLOSED) {
            alert.close(transitionedAt);
            return true;
        }

        throw new ApplicationException(ErrorCode.ALERT_STATUS_TRANSITION_CONFLICT);
    }
}
