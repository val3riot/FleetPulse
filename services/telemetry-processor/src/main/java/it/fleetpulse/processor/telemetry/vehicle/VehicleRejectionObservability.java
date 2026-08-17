package it.fleetpulse.processor.telemetry.vehicle;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Component
public final class VehicleRejectionObservability {

    private static final Logger log = LoggerFactory.getLogger(VehicleRejectionObservability.class);

    private final Map<TelemetryRejectionReason, Counter> counters;

    public VehicleRejectionObservability(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        counters = new EnumMap<>(TelemetryRejectionReason.class);

        for (TelemetryRejectionReason reason : TelemetryRejectionReason.values()) {
            counters.put(reason, Counter.builder("fleetpulse.processor.rejections")
                    .description("Telemetry events rejected by vehicle eligibility")
                    .tag("reason", reason.name())
                    .register(registry));
        }
    }

    public void rejected(TelemetryRejectedEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        counters.get(event.reason()).increment();

        log.info(
                "Telemetry event rejected: messageId={}, vehicleId={}, reason={}, sourceTopic={}, sourcePartition={}, sourceOffset={}",
                event.messageId(),
                event.vehicleId(),
                event.reason(),
                event.sourceTopic(),
                event.sourcePartition(),
                event.sourceOffset()
        );
    }
}
