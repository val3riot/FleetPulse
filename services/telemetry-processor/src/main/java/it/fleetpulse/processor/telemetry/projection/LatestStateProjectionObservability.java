package it.fleetpulse.processor.telemetry.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

@Component
public final class LatestStateProjectionObservability {
    private static final Logger log = LoggerFactory.getLogger(LatestStateProjectionObservability.class);
    private static final long WARNING_INTERVAL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final String UPDATES = "fleetpulse.telemetry.latest_state.updates";

    private final Counter updated;
    private final Counter skipped;
    private final Counter failed;
    private final Counter redisFailures;
    private final LongSupplier nanoTime;
    private final AtomicReference<Long> lastWarning = new AtomicReference<>();

    @Autowired
    public LatestStateProjectionObservability(MeterRegistry registry) {
        this(registry, System::nanoTime);
    }

    LatestStateProjectionObservability(MeterRegistry registry, LongSupplier nanoTime) {
        Objects.requireNonNull(registry);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        updated = registry.counter(UPDATES, "outcome", "updated");
        skipped = registry.counter(UPDATES, "outcome", "skipped");
        failed = registry.counter(UPDATES, "outcome", "failed");
        redisFailures = registry.counter("fleetpulse.redis.update.failures");
    }

    public void completed(UUID messageId, LatestVehicleState candidate, ProjectionUpdateResult result) {
        switch (result) {
            case UPDATED -> updated.increment();
            case SKIPPED -> skipped.increment();
        }
        log.debug("Latest state projection completed: messageId={}, vehicleId={}, sequenceNumber={}, outcome={}",
            messageId, candidate.vehicleId(), candidate.lastSequenceNumber(), result);
    }

    public void failed(UUID messageId, LatestVehicleState candidate, LatestStateProjectionException failure) {
        failed.increment();
        redisFailures.increment();
        long now = nanoTime.getAsLong();
        Long previous = lastWarning.get();
        if ((previous == null || now - previous >= WARNING_INTERVAL_NANOS)
                && lastWarning.compareAndSet(previous, now)) {
            // Exception messages can contain serialized telemetry; log only the error type.
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            log.warn("Latest state projection failed after PostgreSQL commit: "
                    + "messageId={}, vehicleId={}, sequenceNumber={}, errorType={}",
                messageId, candidate.vehicleId(), candidate.lastSequenceNumber(), cause.getClass().getSimpleName());
        }
    }
}
