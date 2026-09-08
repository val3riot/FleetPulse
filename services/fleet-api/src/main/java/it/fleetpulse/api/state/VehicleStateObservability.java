package it.fleetpulse.api.state;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class VehicleStateObservability {
    private static final Logger log = LoggerFactory.getLogger(VehicleStateObservability.class);
    private final Counter hits;
    private final Counter misses;
    private final Counter failures;
    private final Counter fallbacks;
    private final Counter repairFailures;
    private final AtomicLong lastWarning = new AtomicLong(System.nanoTime()
        - Duration.ofSeconds(30).toNanos());

    public VehicleStateObservability(MeterRegistry registry) {
        hits = registry.counter("fleetpulse.api.cache.hits");
        misses = registry.counter("fleetpulse.api.cache.misses");
        failures = registry.counter("fleetpulse.api.cache.failures");
        fallbacks = registry.counter("fleetpulse.api.cache.fallback");
        repairFailures = registry.counter("fleetpulse.api.cache.repair.failures");
    }

    public void hit() { hits.increment(); }
    public void miss() { misses.increment(); }
    public void fallback() { fallbacks.increment(); }

    public void failure(boolean repair, LatestStateProjectionException failure) {
        (repair ? repairFailures : failures).increment();
        long previous = lastWarning.get();
        long now = System.nanoTime();
        if (now - previous >= Duration.ofSeconds(30).toNanos()
                && lastWarning.compareAndSet(previous, now)) {
            log.warn("State cache operation failed: operation={}, errorType={}",
                repair ? "repair" : "read", failure.getClass().getSimpleName());
        }
    }
}
