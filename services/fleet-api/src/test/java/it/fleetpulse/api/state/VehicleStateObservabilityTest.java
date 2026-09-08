package it.fleetpulse.api.state;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class VehicleStateObservabilityTest {
    @Test
    void countsEveryFailureButLimitsWarningsAndDoesNotLogPayload() {
        var metrics = new SimpleMeterRegistry();
        var observations = new VehicleStateObservability(metrics);
        Logger logger = (Logger) LoggerFactory.getLogger(VehicleStateObservability.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < 100; i++) {
                observations.failure(false, new LatestStateProjectionException("private payload"));
                observations.failure(true, new LatestStateProjectionException("private payload"));
            }
            assertThat(metrics.get("fleetpulse.api.cache.failures").counter().count()).isEqualTo(100);
            assertThat(metrics.get("fleetpulse.api.cache.repair.failures").counter().count())
                .isEqualTo(100);
            assertThat(metrics.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).isEmpty());
            assertThat(appender.list).hasSize(1);
            assertThat(appender.list.getFirst().getFormattedMessage()).doesNotContain("private payload");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
            metrics.close();
        }
    }
}
