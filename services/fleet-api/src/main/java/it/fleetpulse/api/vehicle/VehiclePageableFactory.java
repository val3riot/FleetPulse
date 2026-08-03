package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class VehiclePageableFactory {
    private static final int MAX_PAGE_SIZE = 100;
    /**
     * La chiave è il nome esposto dall'API.
     * Il valore è la proprietà dell'entity usata da Spring Data.
     */
    private static final Map<String, String> ALLOWED_SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "externalCode", "externalCode",
            "plate", "plate",
            "status", "status"
    );

    /**
     * Crea il pageable validando pagina, dimensione e ordinamento richiesti.
     */
    public Pageable create(
            int page,
            int size,
            String sortExpression
    ) {
        validatePagination(page, size);

        ParsedSort parsedSort = parseSort(sortExpression);

        Sort sort = Sort.by(parsedSort.direction(), parsedSort.entityProperty())
                .and(Sort.by(Sort.Direction.ASC, "id"));

        return PageRequest.of(page, size, sort);
    }

    /**
     * Verifica che pagina e dimensione rispettino i limiti dell'API.
     */
    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw invalidRequest();
        }

        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalidRequest();
        }
    }

    /**
     * Converte il parametro sort nel campo e nella direzione ammessi.
     */
    private ParsedSort parseSort(String sortExpression) {
        if (sortExpression == null || sortExpression.isBlank()) {
            return new ParsedSort(
                    "createdAt",
                    Sort.Direction.DESC
            );
        }

        String[] parts = sortExpression.trim().split(",", -1);

        if (parts.length != 2) {
            throw invalidRequest();
        }

        String apiField = parts[0].trim();
        String directionValue = parts[1]
                .trim()
                .toLowerCase(Locale.ROOT);

        String entityProperty = ALLOWED_SORT_FIELDS.get(apiField);

        if (entityProperty == null) {
            throw invalidRequest();
        }

        Sort.Direction direction = switch (directionValue) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw invalidRequest();
        };

        return new ParsedSort(entityProperty, direction);
    }

    /**
     * Costruisce l'eccezione applicativa per un parametro non valido.
     */
    private ApplicationException invalidRequest() {
        return new ApplicationException(ErrorCode.REQUEST_INVALID);
    }

    private record ParsedSort(
            String entityProperty,
            Sort.Direction direction
    ) {
    }
}
