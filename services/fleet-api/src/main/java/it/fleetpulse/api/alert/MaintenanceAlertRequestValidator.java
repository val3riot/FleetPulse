package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.springframework.stereotype.Component;

@Component
public class MaintenanceAlertRequestValidator {

    public void validate(MaintenanceAlertSearchRequest request) {
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw new ApplicationException(ErrorCode.REQUEST_INVALID_TIME_RANGE);
        }
    }
}
