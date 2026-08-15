package it.fleetpulse.processor.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public final class TelemetryEventProcessingService implements TelemetryEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryEventProcessingService.class);

    @Override
    public void handle(TelemetryEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.eventVersion() != TelemetryEventVersions.V1) {
            throw new UnsupportedTelemetryEventVersionException(
                    event.eventVersion()
            );
        }

        log.debug(
                "Telemetry event accepted for processing: messageId={}, vehicleId={}, sequenceNumber={}",
                event.messageId(),
                event.vehicleId(),
                event.sequenceNumber()
        );
    }

}
