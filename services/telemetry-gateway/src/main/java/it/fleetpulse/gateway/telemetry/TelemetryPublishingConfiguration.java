package it.fleetpulse.gateway.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.gateway.tcp.FrameHandler;
import it.fleetpulse.gateway.telemetry.kafka.KafkaPublisherProperties;
import it.fleetpulse.gateway.telemetry.kafka.KafkaTelemetryPublisher;
import it.fleetpulse.gateway.telemetry.kafka.KafkaTopicsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
        KafkaTopicsProperties.class,
        KafkaPublisherProperties.class
})
public class TelemetryPublishingConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    TelemetryEventMapper telemetryEventMapper(Clock clock) {
        return new TelemetryEventMapper(clock);
    }

    @Bean
    TelemetryPublisher telemetryPublisher(
            KafkaTemplate<String, TelemetryEvent> kafkaTemplate,
            KafkaTopicsProperties topics
    ) {
        return new KafkaTelemetryPublisher(
                kafkaTemplate,
                topics.raw()
        );
    }

    @Bean
    TelemetryPublishingMetrics telemetryPublishingMetrics(MeterRegistry registry) {
        return new TelemetryPublishingMetrics(registry);
    }

    @Bean
    FrameHandler frameHandler(
            TelemetryEventMapper mapper,
            TelemetryPublisher publisher,
            KafkaPublisherProperties properties,
            TelemetryPublishingMetrics metrics
    ) {
        return new PublishingFrameHandler(
                mapper,
                publisher,
                properties,
                metrics
        );
    }
}
