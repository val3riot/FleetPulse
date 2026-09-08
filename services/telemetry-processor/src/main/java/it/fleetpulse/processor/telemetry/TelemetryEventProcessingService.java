package it.fleetpulse.processor.telemetry;

import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.persistence.TelemetryPersistenceFailureClassifier;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleEntity;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleMapper;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleWriter;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjection;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionObservability;
import it.fleetpulse.processor.telemetry.projection.LatestVehicleState;
import it.fleetpulse.processor.telemetry.vehicle.VehicleEligibilityGuard;

@Service
public final class TelemetryEventProcessingService implements TelemetryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEventProcessingService.class);

    private final TelemetrySampleWriter writer;
    private final TelemetryPersistenceFailureClassifier failureClassifier;
    private final TelemetrySampleMapper mapper;
    private final Clock clock;
    private final VehicleEligibilityGuard eligibilityGuard;
    private final LatestStateProjection latestStateProjection;
    private final LatestStateProjectionObservability projectionObservability;

    public TelemetryEventProcessingService(TelemetrySampleWriter writer,
            TelemetrySampleMapper mapper, Clock clock,
            TelemetryPersistenceFailureClassifier failureClassifier,
            VehicleEligibilityGuard eligibilityGuard,
            LatestStateProjection latestStateProjection,
            LatestStateProjectionObservability projectionObservability) {
        this.writer = Objects.requireNonNull(writer);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
        this.failureClassifier = Objects.requireNonNull(failureClassifier);
        this.eligibilityGuard = Objects.requireNonNull(eligibilityGuard, "eligibilityGuard must not be null");
        this.latestStateProjection = Objects.requireNonNull(latestStateProjection,
                "latestStateProjection must not be null");
        this.projectionObservability = Objects.requireNonNull(projectionObservability);
    }

    @Override
    public void handle(TelemetryEvent event, TelemetrySource source) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (event.eventVersion() != TelemetryEventVersions.V1) {
            throw new UnsupportedTelemetryEventVersionException(event.eventVersion());
        }

        if (eligibilityGuard.rejectIfIneligible(event, source)) {
            return;
        }
        TelemetrySampleEntity entity = mapper.toEntity(event, clock.instant());

        TelemetrySampleEntity saved;
        try {
            saved = writer.insert(entity);
        } catch (DataIntegrityViolationException failure) {
            if (!failureClassifier.isDuplicateMessageId(failure)) {
                throw failure;
            }

            log.info(
                    "Duplicate telemetry event ignored: messageId={}, vehicleId={}, sequenceNumber={}",
                    event.messageId(), event.vehicleId(), event.sequenceNumber());

            return;
        }

        log.info(
                "Telemetry event persisted: sampleId={}, messageId={}, vehicleId={}, sequenceNumber={}",
                saved.getId(), saved.getMessageId(), saved.getVehicleId(), saved.getSequenceNumber());
        // The writer's transactional proxy has committed before returning to this orchestrator.
        updateLatestState(saved);
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

        try {
            var result = latestStateProjection.updateIfNewer(candidate);

            projectionObservability.completed(saved.getMessageId(), candidate, result);
        } catch (LatestStateProjectionException failure) {
            projectionObservability.failed(saved.getMessageId(), candidate, failure);
        }
    }
}
