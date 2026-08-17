package it.fleetpulse.gateway.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.gateway.telemetry.kafka.KafkaPublisherProperties;
import it.fleetpulse.protocol.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PublishingFrameHandlerTest {

    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");

    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    private static final Instant RECEIVED_AT = Instant.parse("2026-08-01T10:15:30.083Z");

    @Test
    void acceptsOnlyAfterPublicationCompletesSuccessfully() throws Exception {
        CompletableFuture<Void> publication = new CompletableFuture<>();
        CountDownLatch publisherInvoked = new CountDownLatch(1);

        PublishingFrameHandler handler = handler(event -> {
            publisherInvoked.countDown();
            return publication;
        }, Duration.ofSeconds(1));

        CompletableFuture<TelemetryAck> handling =
            CompletableFuture.supplyAsync(() -> handler.handle(message()));

        assertTrue(publisherInvoked.await(1, TimeUnit.SECONDS));
        assertFalse(handling.isDone());

        publication.complete(null);

        TelemetryAck result = handling.get(1, TimeUnit.SECONDS);

        assertEquals(
            new TelemetryAck(ProtocolConstants.PROTOCOL_VERSION, MESSAGE_ID, AckStatus.ACCEPTED,
                RECEIVED_AT, null), result);
    }

    @Test
    void rejectsAsynchronousPublicationFailure() {
        PublishingFrameHandler handler = handler(
            event -> CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")),
            Duration.ofSeconds(1));

        TelemetryAck result = handler.handle(message());

        assertRejected(result);
    }

    @Test
    void rejectsPublicationTimeout() {
        PublishingFrameHandler handler =
            handler(event -> new CompletableFuture<>(), Duration.ofMillis(10));

        TelemetryAck result = handler.handle(message());

        assertRejected(result);
    }

    @Test
    void rejectsSynchronousPublisherFailure() {
        PublishingFrameHandler handler = handler(event -> {
            throw new IllegalStateException("send failed");
        }, Duration.ofSeconds(1));

        TelemetryAck result = handler.handle(message());

        assertRejected(result);
    }

    private static PublishingFrameHandler handler(TelemetryPublisher publisher, Duration timeout) {
        Clock clock = Clock.fixed(RECEIVED_AT, ZoneOffset.UTC);

        return new PublishingFrameHandler(new TelemetryEventMapper(clock), publisher,
            new KafkaPublisherProperties(timeout),
            new TelemetryPublishingMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void recordsAcknowledgementLatencyForAckAndNackAndCountsOnlyFailures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryPublishingMetrics metrics = new TelemetryPublishingMetrics(registry);
        PublishingFrameHandler failingHandler = new PublishingFrameHandler(
            new TelemetryEventMapper(Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)),
            event -> CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")),
            new KafkaPublisherProperties(Duration.ofSeconds(1)), metrics);
        PublishingFrameHandler successfulHandler = new PublishingFrameHandler(
            new TelemetryEventMapper(Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)),
            event -> CompletableFuture.completedFuture(null),
            new KafkaPublisherProperties(Duration.ofSeconds(1)), metrics);

        failingHandler.handle(message());
        successfulHandler.handle(message());

        assertEquals(1.0, registry.get("fleetpulse.gateway.publish.failures").counter().count());
        assertEquals(2, registry.get("fleetpulse.gateway.ack.latency").timer().count());
    }

    private static void assertRejected(TelemetryAck result) {
        assertEquals(
            new TelemetryAck(ProtocolConstants.PROTOCOL_VERSION, MESSAGE_ID, AckStatus.REJECTED,
                RECEIVED_AT, ProtocolErrorCode.UPSTREAM_UNAVAILABLE), result);
    }

    private static TelemetryMessage message() {
        return new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION, MESSAGE_ID, VEHICLE_ID, 42,
            Instant.parse("2026-08-01T10:15:30Z"), 72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
    }

}
