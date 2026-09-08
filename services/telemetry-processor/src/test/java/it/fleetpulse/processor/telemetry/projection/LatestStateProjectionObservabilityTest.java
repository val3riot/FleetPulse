package it.fleetpulse.processor.telemetry.projection;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LatestStateProjectionObservabilityTest {
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong time = new AtomicLong();
    private final LatestStateProjectionObservability observability =
        new LatestStateProjectionObservability(registry, time::get);
    private final UUID messageId = UUID.randomUUID();
    private final LatestVehicleState candidate = new LatestVehicleState(UUID.randomUUID(), 42,
        Instant.parse("2026-08-01T10:15:30Z"), 72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);

    @Test
    void registersFixedOutcomesAndCountsEachOperationOnce() {
        assertThat(count("updated")).isZero();
        assertThat(count("skipped")).isZero();
        assertThat(count("failed")).isZero();

        observability.completed(messageId, candidate, ProjectionUpdateResult.UPDATED);
        observability.completed(messageId, candidate, ProjectionUpdateResult.SKIPPED);
        observability.failed(messageId, candidate, new LatestStateProjectionException("Unavailable"));

        assertThat(count("updated")).isEqualTo(1);
        assertThat(count("skipped")).isEqualTo(1);
        assertThat(count("failed")).isEqualTo(1);
        assertThat(registry.get("fleetpulse.redis.update.failures").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).hasSize(4);
        registry.find("fleetpulse.telemetry.latest_state.updates").counters().forEach(counter ->
            assertThat(counter.getId().getTags()).hasSize(1).allSatisfy(tag ->
                assertThat(tag.getKey()).isEqualTo("outcome")));
        assertThat(registry.get("fleetpulse.redis.update.failures").counter().getId().getTags()).isEmpty();
    }

    @Test
    void exportsFailureCounterWithCatalogName() {
        var prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try {
            var observed = new LatestStateProjectionObservability(prometheus, time::get);
            observed.failed(messageId, candidate, new LatestStateProjectionException("Unavailable"));

            assertThat(prometheus.scrape())
                .contains("fleetpulse_redis_update_failures_total 1.0")
                .contains("fleetpulse_telemetry_latest_state_updates_total{outcome=\"failed\"} 1.0");
        } finally {
            prometheus.close();
        }
    }

    @Test
    void limitsWarningsButCountsEveryFailureWithoutLoggingPayload() {
        var logger = (Logger) LoggerFactory.getLogger(LatestStateProjectionObservability.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var failure = new LatestStateProjectionException("SECRET_PAYLOAD",
            new IllegalArgumentException("SECRET_PAYLOAD"));
        try {
            observability.failed(messageId, candidate, failure);
            observability.failed(messageId, candidate, failure);
            time.set(Duration.ofSeconds(30).toNanos() - 1);
            observability.failed(messageId, candidate, failure);
            assertThat(appender.list).hasSize(1);

            time.incrementAndGet();
            observability.failed(messageId, candidate, failure);
            assertThat(appender.list).hasSize(2).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains(messageId.toString(), "IllegalArgumentException")
                    .doesNotContain("SECRET_PAYLOAD");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(count("failed")).isEqualTo(4);
            assertThat(registry.get("fleetpulse.redis.update.failures").counter().count()).isEqualTo(4);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    private double count(String outcome) {
        return registry.get("fleetpulse.telemetry.latest_state.updates")
            .tag("outcome", outcome).counter().count();
    }
}
