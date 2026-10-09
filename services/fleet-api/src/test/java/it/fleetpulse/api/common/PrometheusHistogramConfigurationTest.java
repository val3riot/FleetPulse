package it.fleetpulse.api.common;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class PrometheusHistogramConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                    PrometheusMetricsExportAutoConfiguration.class))
            .withPropertyValues("management.metrics.use-global-registry=false")
            .withInitializer(context -> {
                try {
                    // Read the service's actual YAML, including its environment placeholders.
                    new YamlPropertySourceLoader()
                            .load("application", new ClassPathResource("application.yaml"))
                            .forEach(source -> context.getEnvironment().getPropertySources()
                                    .addLast(source));
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            });

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exportsConfiguredHistogramsInSeconds(boolean overridden) {
        ApplicationContextRunner configured = overridden
                ? runner.withPropertyValues("METRICS_TIMER_MIN=10ms", "METRICS_TIMER_MAX=5s",
                        "METRICS_TIMER_BUCKETS=750ms,2s,5s")
                : runner;
        configured.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(PrometheusMeterRegistry.class);
            PrometheusMeterRegistry registry = context.getBean(PrometheusMeterRegistry.class);
            registry.timer("http.server.requests", "method", "GET", "uri",
                    "/api/v1/vehicles/{vehicleId}");
            // Record deterministic durations on timers registered by production code.
            registry.getMeters().stream()
                    .filter(meter -> meter instanceof Timer)
                    .map(meter -> (Timer) meter)
                    .forEach(timer -> {
                        timer.record(Duration.ofMillis(100));
                        var buckets = Arrays.stream(timer.takeSnapshot().histogramCounts())
                                .mapToDouble(bucket -> bucket.bucket()).filter(Double::isFinite)
                                .boxed().toList();
                        assertThat(buckets).isNotEmpty().allSatisfy(bucket -> assertThat(bucket)
                                .isBetween(overridden ? 10_000_000.0 : 1_000_000.0,
                                        overridden ? 5_000_000_000.0 : 30_000_000_000.0));
                    });
            String export = registry.scrape();
            for (String metric : List.of("http_server_requests_seconds")) {
                var lines = export.lines().filter(line -> line.startsWith(metric + "_")).toList();
                assertThat(lines).anyMatch(
                        line -> line.startsWith(metric + "_count") && line.endsWith(" 1"));
                assertThat(lines).anyMatch(
                        line -> line.startsWith(metric + "_sum") && line.endsWith(" 0.1"));
                assertThat(lines).anyMatch(line -> line.startsWith(metric + "_bucket{")
                        && line.contains("le=\"+Inf\"") && line.endsWith(" 1"));
                assertThat(lines).anyMatch(line -> line.startsWith(metric + "_bucket{")
                        && line.contains("le=\"2.0\"") && line.endsWith(" 1"));
                assertThat(lines).anyMatch(line -> line.startsWith(metric + "_bucket{")
                        && line.contains(overridden ? "le=\"0.75\"" : "le=\"0.05\"")
                        && line.endsWith(overridden ? " 1" : " 0"));
                if (overridden) {
                    assertThat(lines).noneMatch(line -> line.contains("le=\"30.0\""));
                }
            }
        });
    }
}
