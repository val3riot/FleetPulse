package it.fleetpulse.api.vehicle;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class VehicleSpecifications {

    private VehicleSpecifications() {
    }

    /**
     * Costruisce i predicati di ricerca dei veicoli in base ai filtri ricevuti.
     */
    public static Specification<VehicleEntity> from(VehicleSearchCriteria criteria) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(criteria.query())) {
                String normalizedQuery = criteria.query().trim().toLowerCase(Locale.ROOT);

                String pattern = "%" + normalizedQuery + "%";

                predicates.add(
                    builder.or(builder.like(builder.lower(root.get("externalCode")), pattern),
                        builder.like(builder.lower(root.get("plate")), pattern)));
            }

            if (criteria.status() != null) {
                predicates.add(builder.equal(root.get("status"), criteria.status()));
            }

            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
