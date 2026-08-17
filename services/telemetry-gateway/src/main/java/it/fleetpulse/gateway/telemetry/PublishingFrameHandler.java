package it.fleetpulse.gateway.telemetry;

import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.gateway.tcp.FrameHandler;
import it.fleetpulse.gateway.telemetry.kafka.KafkaPublisherProperties;
import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.ProtocolErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class PublishingFrameHandler implements FrameHandler {
    private static final Logger log = LoggerFactory.getLogger(PublishingFrameHandler.class);

    private final TelemetryEventMapper mapper;
    private final TelemetryPublisher publisher;
    private final KafkaPublisherProperties properties;
    private final TelemetryPublishingMetrics metrics;

    public PublishingFrameHandler(TelemetryEventMapper mapper, TelemetryPublisher publisher,
        KafkaPublisherProperties properties, TelemetryPublishingMetrics metrics) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public TelemetryAck handle(TelemetryMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        TelemetryEvent event = mapper.map(message);
        Timer.Sample acknowledgement = metrics.startAcknowledgement();
        try {
            publisher.publish(event).toCompletableFuture()
                .get(properties.confirmationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return accepted(event);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            metrics.publicationFailed();
            log.warn("Kafka publication interrupted: messageId={}, vehicleId={}", event.messageId(),
                event.vehicleId());
            return rejected(event);
        } catch (ExecutionException | TimeoutException | RuntimeException exception) {
            metrics.publicationFailed();
            log.warn("Kafka publication not confirmed: messageId={}, vehicleId={}, failure={}",
                event.messageId(), event.vehicleId(), exception.getClass().getSimpleName());
            return rejected(event);
        } finally {
            metrics.completeAcknowledgement(acknowledgement);
        }
    }

    private static TelemetryAck accepted(TelemetryEvent event) {
        return new TelemetryAck(ProtocolConstants.PROTOCOL_VERSION, event.messageId(),
            AckStatus.ACCEPTED, event.receivedAt(), null);
    }

    private static TelemetryAck rejected(TelemetryEvent event) {
        return new TelemetryAck(ProtocolConstants.PROTOCOL_VERSION, event.messageId(),
            AckStatus.REJECTED, event.receivedAt(), ProtocolErrorCode.UPSTREAM_UNAVAILABLE);
    }
}
