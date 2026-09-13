package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Costruisce un {@link Pageable} bounded e deterministico per lo storico.
 */
@Component
public class TelemetryHistoryPageableFactory {
    private static final int MAX_PAGE_SIZE = 100;

    public Pageable create(int page, int size, String sortExpression) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalidRequest();
        }

        Sort.Direction direction = parseDirection(sortExpression);
        Sort sort = Sort.by(direction, "observedAt").and(Sort.by(direction, "id"));
        return PageRequest.of(page, size, sort);
    }

    private Sort.Direction parseDirection(String sortExpression) {
        if (sortExpression == null) {
            return Sort.Direction.DESC;
        }

        String[] parts = sortExpression.trim().split(",", -1);
        if (parts.length != 2 || !"observedAt".equals(parts[0].trim())) {
            throw invalidRequest();
        }

        return switch (parts[1].trim().toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw invalidRequest();
        };
    }

    private ApplicationException invalidRequest() {
        return new ApplicationException(ErrorCode.REQUEST_INVALID);
    }
}
