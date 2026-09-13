package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Valida le invarianti semantiche della ricerca dello storico.
 */
@Component
public class TelemetryHistoryRequestValidator {

    /**
     * Rifiuta un intervallo la cui fine precede l'inizio.
     */
    public void validate(TelemetryHistoryRequest request) {
        if (request.from() != null && request.to() != null && request.from().isAfter(request.to())) {
            throw new ApplicationException(ErrorCode.REQUEST_INVALID_TIME_RANGE);
        }
    }
}
