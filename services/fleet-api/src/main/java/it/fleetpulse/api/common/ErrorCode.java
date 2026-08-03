package it.fleetpulse.api.common;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    REQUEST_INVALID(
            HttpStatus.BAD_REQUEST,
            "The request is invalid"
    ),
    REQUEST_MALFORMED_JSON(
            HttpStatus.BAD_REQUEST,
            "The request body is malformed"
    ),
    REQUEST_INVALID_TIME_RANGE(
            HttpStatus.BAD_REQUEST,
            "The requested time range is invalid"
    ),
    VEHICLE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Vehicle not found"
    ),
    VEHICLE_STATE_NOT_AVAILABLE(
            HttpStatus.NOT_FOUND,
            "Vehicle state is not available"
    ),
    VEHICLE_PLATE_CONFLICT(
            HttpStatus.CONFLICT,
            "Plate already present"
    ),
    VEHICLE_EXTERNAL_CODE_CONFLICT(
            HttpStatus.CONFLICT,
            "External code already present"
    ),
    ALERT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "Alert not found"
    ),
    ALERT_STATUS_TRANSITION_CONFLICT(
            HttpStatus.CONFLICT,
            "The alert status transition is not allowed"
    ),
    SERVICE_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "A required service is temporarily unavailable"
    ),
    REQUEST_METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "HTTP method not allowed for this resource"
    ),
    REQUEST_UNSUPPORTED_MEDIA_TYPE(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Unsupported request media type"
    ),
    INTERNAL_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred"
    );

    private final String code;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(
            HttpStatus httpStatus,
            String defaultMessage
    ) {
        this.code = name();
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    /**
     * Restituisce il codice pubblico esposto nelle risposte REST.
     */
    public String getCode() {
        return code;
    }

    /**
     * Restituisce lo stato HTTP associato al codice.
     */
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    /**
     * Restituisce il messaggio pubblico predefinito.
     */
    public String getDefaultMessage() {
        return defaultMessage;
    }
}
