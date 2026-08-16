package it.fleetpulse.processor.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleEntity;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleMapper;
import it.fleetpulse.processor.telemetry.persistence.TelemetrySampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

@Service
public class TelemetryEventProcessingService implements TelemetryEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(
                    TelemetryEventProcessingService.class
            );

    private final TelemetrySampleRepository repository;
    private final TelemetrySampleMapper mapper;
    private final Clock clock;

    public TelemetryEventProcessingService(
            TelemetrySampleRepository repository,
            TelemetrySampleMapper mapper,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.mapper = Objects.requireNonNull(mapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public void handle(TelemetryEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.eventVersion() != TelemetryEventVersions.V1) {
            throw new UnsupportedTelemetryEventVersionException(
                    event.eventVersion()
            );
        }
        TelemetrySampleEntity entity = mapper.toEntity(
                event,
                clock.instant()
        );

        TelemetrySampleEntity saved = repository.saveAndFlush(entity);

        log.info(
                "Telemetry event persisted: sampleId={}, messageId={}, vehicleId={}, sequenceNumber={}",
                saved.getId(),
                saved.getMessageId(),
                saved.getVehicleId(),
                saved.getSequenceNumber()
        );
    }

}
