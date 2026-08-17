package it.fleetpulse.processor.telemetry.vehicle;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import it.fleetpulse.processor.telemetry.TelemetrySource;
import it.fleetpulse.processor.telemetry.kafka.TelemetryTerminalEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

@Service
public final class VehicleEligibilityGuard {

    private final VehicleEligibilityEvaluator evaluator;
    private final TelemetryRejectedEventFactory eventFactory;
    private final TelemetryTerminalEventPublisher publisher;
    private final VehicleRejectionObservability observability;

    public VehicleEligibilityGuard(VehicleEligibilityEvaluator evaluator,
        TelemetryRejectedEventFactory eventFactory, TelemetryTerminalEventPublisher publisher,
        VehicleRejectionObservability observability) {
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator must not be null");
        this.eventFactory = Objects.requireNonNull(eventFactory, "eventFactory must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.observability =
            Objects.requireNonNull(observability, "observability must not be null");
    }

    public boolean rejectIfIneligible(TelemetryEvent event, TelemetrySource source) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(source, "source must not be null");

        Optional<TelemetryRejectionReason> reason = evaluator.rejectionReason(event.vehicleId());

        if (reason.isEmpty()) {
            return false;
        }

        TelemetryRejectedEvent rejection = eventFactory.create(event, reason.get(), source);
        publisher.publishRejected(rejection);
        observability.rejected(rejection);

        return true;
    }
}
