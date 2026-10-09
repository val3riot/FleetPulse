package it.fleetpulse.api.common;

import jakarta.servlet.http.HttpServletRequest;

/** Valida i parametri scalari prima che il binding possa scartare valori ambigui. */
public final class QueryParameterValidator {
    private QueryParameterValidator() {
    }

    public static void validate(HttpServletRequest request, String... parameterNames) {
        for (String name : parameterNames) {
            String[] values = request.getParameterValues(name);
            if (values != null && (values.length != 1 || values[0] == null
                    || values[0].isBlank())) {
                throw new ApplicationException(ErrorCode.REQUEST_INVALID);
            }
        }
    }
}
