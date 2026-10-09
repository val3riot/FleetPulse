package it.fleetpulse.processor.telemetry;

import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import it.fleetpulse.processor.telemetry.TelemetryProcessingMetrics.Outcome;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.alert.AlertEvaluator;
import it.fleetpulse.processor.telemetry.alert.AlertCandidate;
import it.fleetpulse.processor.telemetry.alert.AlertTelemetryMapper;
import it.fleetpulse.processor.telemetry.alert.AlertVehicle;
import it.fleetpulse.processor.telemetry.alert.AlertVehicleQuery;
import it.fleetpulse.processor.telemetry.persistence.TelemetryAggregateWriter;
import it.fleetpulse.processor.telemetry.persistence.TelemetryPersistenceFailureClassifier;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleEntity;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleMapper;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjection;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionObservability;
import it.fleetpulse.processor.telemetry.projection.LatestVehicleState;
import it.fleetpulse.processor.telemetry.projection.ProjectionUpdateResult;
import it.fleetpulse.processor.telemetry.vehicle.VehicleEligibilityGuard;

@Service
public final class TelemetryEventProcessingService implements TelemetryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEventProcessingService.class);

    private final TelemetryAggregateWriter writer;
    private final TelemetryPersistenceFailureClassifier failureClassifier;
    private final TelemetrySampleMapper mapper;
    private final Clock clock;
    private final VehicleEligibilityGuard eligibilityGuard;
    private final LatestStateProjection latestStateProjection;
    private final LatestStateProjectionObservability projectionObservability;
    private final AlertVehicleQuery alertVehicleQuery;
    private final AlertTelemetryMapper alertTelemetryMapper;
    private final AlertEvaluator alertEvaluator;
    private final TelemetryProcessingMetrics metrics;

    public TelemetryEventProcessingService(TelemetryAggregateWriter writer,
            TelemetrySampleMapper mapper, Clock clock,
            TelemetryPersistenceFailureClassifier failureClassifier,
            VehicleEligibilityGuard eligibilityGuard,
            LatestStateProjection latestStateProjection,
            LatestStateProjectionObservability projectionObservability,
            AlertVehicleQuery alertVehicleQuery,
            AlertTelemetryMapper alertTelemetryMapper,
            AlertEvaluator alertEvaluator, TelemetryProcessingMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics);
        this.writer = Objects.requireNonNull(writer);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.eligibilityGuard = Objects.requireNonNull(eligibilityGuard,
            "eligibilityGuard must not be null");
        this.latestStateProjection = Objects.requireNonNull(latestStateProjection,
                "latestStateProjection must not be null");
        this.projectionObservability = Objects.requireNonNull(projectionObservability);
        this.alertVehicleQuery =
            Objects.requireNonNull(alertVehicleQuery, "alertVehicleQuery must not be null");
        this.alertTelemetryMapper =
            Objects.requireNonNull(alertTelemetryMapper, "alertTelemetryMapper must not be null");
        this.alertEvaluator = Objects.requireNonNull(alertEvaluator,
            "alertEvaluator must not be null");
    }

    @Override
    public void handle(TelemetryEvent event, TelemetrySource source) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Timer.Sample attempt = metrics.startAttempt();
        ProcessingResult result;
        Outcome outcome = Outcome.FAILED;
        try {
            result = persistAggregate(event, source);
            outcome = result.outcome();
        } finally {
            metrics.completeAttempt(attempt, outcome);
        }
        if (result.sample() != null) {
            updateLatestState(result.sample());
        }
    }

    private record ProcessingResult(Outcome outcome, TelemetrySampleEntity sample) {
        private ProcessingResult {
            Objects.requireNonNull(outcome, "outcome must not be null");
            if ((outcome == Outcome.PERSISTED) != (sample != null)) {
                throw new IllegalArgumentException(
                    "A sample is required exactly when the outcome is persisted");
            }
        }
    }

    private ProcessingResult persistAggregate(TelemetryEvent event, TelemetrySource source) {
        if (event.eventVersion() != TelemetryEventVersions.V1) {
            throw new UnsupportedTelemetryEventVersionException(event.eventVersion());
        }

        if (eligibilityGuard.rejectIfIneligible(event, source)) {
            return new ProcessingResult(Outcome.REJECTED, null);
        }
        Instant processedAt = clock.instant();
        TelemetrySampleEntity entity = mapper.toEntity(event, processedAt);
        AlertVehicle vehicle = alertVehicleQuery.findById(event.vehicleId())
            .orElseThrow(() -> new IllegalStateException(
                "Eligible vehicle is not available for alert evaluation: " + event.vehicleId()));
        List<AlertCandidate> candidates =
            alertEvaluator.evaluate(vehicle, alertTelemetryMapper.toSample(event));

        TelemetrySampleEntity saved;
        try {
            saved = writer.insert(entity, candidates, processedAt).sample();
        } catch (DataIntegrityViolationException failure) {
            if (!failureClassifier.isDuplicateMessageId(failure) &&
                !failureClassifier.isDuplicateAlertSourceType(failure)) {
                throw failure;
            }

            log.atInfo().addKeyValue("event.action", "duplicate.telemetry.aggregate.ignored")
                .addKeyValue("messageId", event.messageId())
                .addKeyValue("vehicleId", event.vehicleId())
                .addKeyValue("sequenceNumber", event.sequenceNumber())
                .log("Duplicate telemetry aggregate ignored: messageId={}, vehicleId={}," +
                    " sequenceNumber={}",

                event.messageId(),
                event.vehicleId(),
                event.sequenceNumber());

            return new ProcessingResult(Outcome.DUPLICATE, null);
        }

        // No outer transaction: the writer proxy has completed the commit before returning.
        Instant committedAt = clock.instant();
        ProcessingResult result = new ProcessingResult(Outcome.PERSISTED, saved);
        Duration persistenceLatency = metrics.recordPersistence(event.receivedAt(), committedAt);
        log.atInfo().addKeyValue("event.action", "telemetry.event.persisted")
            .addKeyValue("pipeline.persistence.completedAt", committedAt)
            .addKeyValue("pipeline.persistence.latency.ms",
                persistenceLatency == null ? null : persistenceLatency.toNanos() / 1_000_000.0)
            .addKeyValue("pipeline.persistence.clock.valid", persistenceLatency != null)
            .addKeyValue("sampleId", saved.getId())
            .addKeyValue("alertCandidates", candidates.size())
            .addKeyValue("messageId", saved.getMessageId())
            .addKeyValue("vehicleId", saved.getVehicleId())
            .addKeyValue("sequenceNumber", saved.getSequenceNumber())
            .log("Telemetry event persisted: sampleId={}, messageId={}, vehicleId={}," +
                " sequenceNumber={}",

                saved.getId(),
                saved.getMessageId(),
                saved.getVehicleId(),
                saved.getSequenceNumber());
        // The writer's transactional proxy has committed before returning to this orchestrator.
        return result;
    }

    private void updateLatestState(TelemetrySampleEntity saved) {
        LatestVehicleState candidate = new LatestVehicleState(
                saved.getVehicleId(),
                saved.getSequenceNumber(),
                saved.getObservedAt(),
                saved.getSpeedKmh(),
                saved.getEngineTemperatureC(),
                saved.getBatteryVoltage(),
                saved.getOdometerKm(),
                saved.getLatitude(),
                saved.getLongitude());

        Timer.Sample update = metrics.startProjection();
        boolean failed = true;
        try {
            ProjectionUpdateResult result = latestStateProjection.updateIfNewer(candidate);

            failed = false;
            projectionObservability.completed(saved.getMessageId(), candidate, result);
        } catch (LatestStateProjectionException failure) {
            projectionObservability.failed(saved.getMessageId(), candidate, failure);
        } finally {
            metrics.completeProjection(update, failed);
        }
    }
}
