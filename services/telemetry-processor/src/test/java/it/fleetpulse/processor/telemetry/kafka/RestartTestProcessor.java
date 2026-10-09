package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.processor.TelemetryProcessorApplication;
import it.fleetpulse.processor.telemetry.TelemetryEventHandler;
import it.fleetpulse.processor.telemetry.TelemetryEventProcessingService;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Fork-only fixture: the crash seam is never present in the production artifact. */
public final class RestartTestProcessor {
    public static void main(String[] args) {
        new SpringApplicationBuilder(TelemetryProcessorApplication.class, BoundaryConfiguration.class)
            .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BoundaryConfiguration {
        @Bean
        @Primary
        TelemetryEventHandler restartBoundary(TelemetryEventProcessingService delegate,
                MeterRegistry registry, Environment environment) {
            return (event, source) -> {
                delegate.handle(event, source);
                // The aggregate transaction has committed, but the listener has not returned.
                Properties evidence = new Properties();
                evidence.setProperty("messageId", event.messageId().toString());
                evidence.setProperty("topic", source.topic());
                evidence.setProperty("partition", Integer.toString(source.partition()));
                evidence.setProperty("offset", Long.toString(source.offset()));
                evidence.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
                for (String name : new String[] {"events", "persisted", "duplicates"}) {
                    evidence.setProperty(name,
                        Double.toString(registry.get("fleetpulse.processor." + name).counter().count()));
                }
                Path destination = Path.of(environment.getRequiredProperty("restart-test.evidence"));
                Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
                try {
                    try (var output = Files.newOutputStream(temporary)) {
                        evidence.store(output, "Processor boundary evidence");
                    }
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException failure) {
                    throw new IllegalStateException("Cannot record restart evidence", failure);
                }
                if (environment.getProperty("restart-test.crash", Boolean.class, false)) {
                    // No Spring shutdown hooks, graceful listener stop or offset commit.
                    Runtime.getRuntime().halt(44);
                }
            };
        }
    }
}
