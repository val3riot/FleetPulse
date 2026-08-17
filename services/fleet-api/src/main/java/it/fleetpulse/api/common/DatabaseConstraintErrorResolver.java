package it.fleetpulse.api.common;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class DatabaseConstraintErrorResolver {
    private static final Map<String, ErrorCode> ERROR_BY_CONSTRAINT =
        Map.of("uq_vehicles_external_code", ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT,

            "uq_vehicles_plate", ErrorCode.VEHICLE_PLATE_CONFLICT);

    /**
     * Converte una violazione di constraint nel codice di errore applicativo.
     */
    public Optional<ErrorCode> resolve(DataIntegrityViolationException exception) {
        return findConstraintName(exception).map(ERROR_BY_CONSTRAINT::get).filter(Objects::nonNull);
    }

    /**
     * Cerca il nome del constraint lungo la catena delle cause.
     */
    private Optional<String> findConstraintName(Throwable throwable) {
        Throwable current = throwable;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return Optional.ofNullable(violation.getConstraintName());
            }

            current = current.getCause();
        }

        return Optional.empty();
    }
}
