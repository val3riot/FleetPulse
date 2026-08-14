package it.fleetpulse.gateway.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.gateway.tcp.FrameHandler;
import it.fleetpulse.gateway.telemetry.kafka.KafkaTelemetryPublisher;
import it.fleetpulse.gateway.telemetry.kafka.KafkaTopicsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import it.fleetpulse.gateway.telemetry.kafka.KafkaPublisherProperties;
import org.springframework.kafka.core.ProducerFactory;

import java.time.Duration;

public class TelemetryPublishingConfigurationTest {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            TelemetryPublishingConfiguration.class
                    )
                    .withBean(
                            KafkaTemplate.class,
                            TelemetryPublishingConfigurationTest::kafkaTemplate
                    )
                    .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void createsPublishingBeansFromConfiguredTopic() {
        contextRunner
                .withPropertyValues(
                        "fleetpulse.kafka.topics.raw=telemetry.test.v1",
                        "fleetpulse.kafka.publisher.confirmation-timeout=750ms"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());

                    KafkaTopicsProperties topics =
                            context.getBean(KafkaTopicsProperties.class);
                    KafkaPublisherProperties publisherProperties =
                            context.getBean(KafkaPublisherProperties.class);

                    assertEquals("telemetry.test.v1", topics.raw());
                    assertNotNull(context.getBean(Clock.class));
                    assertNotNull(context.getBean(TelemetryEventMapper.class));
                    assertEquals(
                            Duration.ofMillis(750),
                            publisherProperties.confirmationTimeout()
                    );

                    TelemetryPublisher publisher =
                            context.getBean(TelemetryPublisher.class);

                    assertInstanceOf(
                            KafkaTelemetryPublisher.class,
                            publisher
                    );

                    FrameHandler frameHandler =
                            context.getBean(FrameHandler.class);

                    assertInstanceOf(
                            PublishingFrameHandler.class,
                            frameHandler
                    );
                });
    }

    @Test
    void rejectsBlankRawTopic() {
        contextRunner
                .withPropertyValues(
                        "fleetpulse.kafka.topics.raw= ",
                        "fleetpulse.kafka.publisher.confirmation-timeout=5s"
                )
                .run(context ->
                        assertNotNull(context.getStartupFailure())
                );
    }

    @Test
    void rejectsMissingConfirmationTimeout() {
        contextRunner
                .withPropertyValues(
                        "fleetpulse.kafka.topics.raw=telemetry.raw.v1"
                )
                .run(context ->
                        assertNotNull(context.getStartupFailure())
                );
    }

    @Test
    void rejectsNonPositiveConfirmationTimeout() {
        contextRunner
                .withPropertyValues(
                        "fleetpulse.kafka.topics.raw=telemetry.raw.v1",
                        "fleetpulse.kafka.publisher.confirmation-timeout=0s"
                )
                .run(context ->
                        assertNotNull(context.getStartupFailure())
                );
    }

    private static KafkaTemplate<String, TelemetryEvent> kafkaTemplate() {
        ProducerFactory<String, TelemetryEvent> producerFactory =
                () -> {
                    throw new AssertionError(
                            "Producer must not be created by a wiring test"
                    );
                };

        return new KafkaTemplate<>(producerFactory);
    }
}
