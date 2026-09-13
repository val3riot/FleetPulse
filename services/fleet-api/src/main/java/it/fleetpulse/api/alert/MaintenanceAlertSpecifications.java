package it.fleetpulse.api.alert;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

public final class MaintenanceAlertSpecifications {

    private MaintenanceAlertSpecifications() {
    }

    public static Specification<MaintenanceAlertEntity> from(
        MaintenanceAlertSearchCriteria criteria) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.vehicleId() != null) {
                predicates.add(builder.equal(root.get("vehicleId"), criteria.vehicleId()));
            }
            if (criteria.status() != null) {
                predicates.add(builder.equal(root.get("status"), criteria.status()));
            }
            if (criteria.type() != null) {
                predicates.add(builder.equal(root.get("type"), criteria.type()));
            }
            if (criteria.severity() != null) {
                predicates.add(builder.equal(root.get("severity"), criteria.severity()));
            }
            if (criteria.from() != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("createdAt"), criteria.from()));
            }
            if (criteria.to() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("createdAt"), criteria.to()));
            }

            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
