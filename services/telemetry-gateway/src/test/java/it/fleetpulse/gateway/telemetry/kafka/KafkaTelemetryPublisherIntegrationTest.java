package it.fleetpulse.gateway.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.gateway.tcp.*;
import it.fleetpulse.gateway.telemetry.PublishingFrameHandler;
import it.fleetpulse.gateway.telemetry.TelemetryEventMapper;
import it.fleetpulse.gateway.telemetry.TelemetryPublishingMetrics;
import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.ProtocolErrorCode;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.TelemetryMessage;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Testcontainers
public class KafkaTelemetryPublisherIntegrationTest {
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    private static final String TOPIC = "telemetry.raw.v1";
    private static DefaultKafkaProducerFactory<String, TelemetryEvent> producerFactory;
    private static KafkaTelemetryPublisher publisher;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    @Test
    void startsKafkaBroker() {
        assertFalse(KAFKA.getBootstrapServers().isBlank());
    }

    @BeforeAll
    static void createTopic() throws Exception {
        Map<String, Object> configuration = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        );

        try (AdminClient adminClient = AdminClient.create(configuration)) {
            adminClient.createTopics(
                    List.of(new NewTopic(TOPIC, 3, (short) 1))
            ).all().get(10, TimeUnit.SECONDS);
        }
        Map<String, Object> producerConfiguration = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true
        );
        producerFactory = new DefaultKafkaProducerFactory<>(producerConfiguration);

        KafkaTemplate<String, TelemetryEvent> kafkaTemplate =
                new KafkaTemplate<>(producerFactory);

        publisher = new KafkaTelemetryPublisher(kafkaTemplate, TOPIC);
    }

    @AfterAll
    static void closeProducer() {
        producerFactory.destroy();
    }

    @Test
    void publishesEventWithExpectedTopicKeyAndPayload() throws Exception {
        TelemetryEvent expectedEvent = event();

        Properties consumerConfiguration = new Properties();
        consumerConfiguration.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        );
        consumerConfiguration.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "gateway-integration-test-" + UUID.randomUUID()
        );
        consumerConfiguration.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );
        consumerConfiguration.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );

        try (
                KafkaConsumer<String, TelemetryEvent> consumer =
                        new KafkaConsumer<>(
                                consumerConfiguration,
                                new StringDeserializer(),
                                new JacksonJsonDeserializer<>(TelemetryEvent.class)
                        )
        ) {
            consumer.subscribe(List.of(TOPIC));

            publisher.publish(expectedEvent)
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            ConsumerRecords<String, TelemetryEvent> records =
                    consumer.poll(Duration.ofSeconds(10));

            assertFalse(records.isEmpty());

            ConsumerRecord<String, TelemetryEvent> record =
                    records.iterator().next();

            assertEquals(TOPIC, record.topic());
            assertEquals(expectedEvent.vehicleId().toString(), record.key());
            assertEquals(expectedEvent, record.value());
        }
    }

    @Test
    void returnsNackWhenKafkaIsUnavailable() throws Exception{
        Map<String, Object> unavailableKafkaConfiguration = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 250,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 250,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 500
        );

        DefaultKafkaProducerFactory<String, TelemetryEvent> unavailableFactory =
                new DefaultKafkaProducerFactory<>(unavailableKafkaConfiguration);

        try {
            KafkaTelemetryPublisher unavailablePublisher =
                    new KafkaTelemetryPublisher(
                            new KafkaTemplate<>(unavailableFactory),
                            TOPIC
                    );

            Instant receivedAt =
                    Instant.parse("2026-08-01T10:15:30.083Z");

            PublishingFrameHandler handler = new PublishingFrameHandler(
                    new TelemetryEventMapper(
                            Clock.fixed(receivedAt, ZoneOffset.UTC)
                    ),
                    unavailablePublisher,
                    new KafkaPublisherProperties(Duration.ofSeconds(1)),
                    new TelemetryPublishingMetrics(new SimpleMeterRegistry())
            );

            TelemetryAck result = exchangeOverTcp(handler, message());

            assertEquals(
                    new TelemetryAck(
                            ProtocolConstants.PROTOCOL_VERSION,
                            message().messageId(),
                            AckStatus.REJECTED,
                            receivedAt,
                            ProtocolErrorCode.UPSTREAM_UNAVAILABLE
                    ),
                    result
            );
        } finally {
            unavailableFactory.destroy();
        }
    }

    @Test
    void returnsAcceptedAfterRealKafkaAcknowledgement() {
        Instant receivedAt =
                Instant.parse("2026-08-01T10:15:30.083Z");

        PublishingFrameHandler handler = new PublishingFrameHandler(
                new TelemetryEventMapper(
                        Clock.fixed(receivedAt, ZoneOffset.UTC)
                ),
                publisher,
                new KafkaPublisherProperties(Duration.ofSeconds(2)),
                new TelemetryPublishingMetrics(new SimpleMeterRegistry())
        );

        TelemetryMessage input = message();

        TelemetryAck result = handler.handle(input);

        assertEquals(
                new TelemetryAck(
                        ProtocolConstants.PROTOCOL_VERSION,
                        input.messageId(),
                        AckStatus.ACCEPTED,
                        receivedAt,
                        null
                ),
                result
        );
    }

    @Test
    void writesAcceptedToRealSocketAfterKafkaAcknowledgement() throws Exception {
        Instant receivedAt =
                Instant.parse("2026-08-01T10:15:30.083Z");

        PublishingFrameHandler handler = new PublishingFrameHandler(
                new TelemetryEventMapper(
                        Clock.fixed(receivedAt, ZoneOffset.UTC)
                ),
                publisher,
                new KafkaPublisherProperties(Duration.ofSeconds(2)),
                new TelemetryPublishingMetrics(new SimpleMeterRegistry())
        );

        TcpServer server = new TcpServer(
                handler,
                new TcpServerProperties(
                        true,
                        0,
                        1,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)
                ),
                new FrameDecoder(OBJECT_MAPPER),
                new TelemetryAckEncoder(OBJECT_MAPPER),
                new SimpleMeterRegistry()
        );

        CompletableFuture<Integer> boundPort = new CompletableFuture<>();

        Thread listener = Thread.ofPlatform().start(() -> {
            try {
                server.start(boundPort);
            } catch (IOException exception) {
                boundPort.completeExceptionally(exception);
            }
        });

        try {
            int port = boundPort.get(2, TimeUnit.SECONDS);
            TelemetryMessage input = message();

            try (
                    Socket client = new Socket(
                            InetAddress.getLoopbackAddress(),
                            port
                    )
            ) {
                client.setSoTimeout(3_000);

                LengthPrefixedFrameCodec.write(
                        OBJECT_MAPPER.writeValueAsBytes(input),
                        client.getOutputStream()
                );

                byte[] acknowledgementPayload =
                        LengthPrefixedFrameCodec.read(
                                client.getInputStream()
                        );

                TelemetryAck acknowledgement =
                        OBJECT_MAPPER.readValue(
                                acknowledgementPayload,
                                TelemetryAck.class
                        );

                assertEquals(AckStatus.ACCEPTED, acknowledgement.status());
                assertEquals(input.messageId(), acknowledgement.messageId());
                assertEquals(receivedAt, acknowledgement.receivedAt());
                assertEquals(null, acknowledgement.errorCode());
            }
        } finally {
            server.close();
            listener.join(2_000);
        }
    }

    private static TelemetryAck exchangeOverTcp(
            FrameHandler handler,
            TelemetryMessage input
    ) throws Exception {
        TcpServer server = new TcpServer(
                handler,
                new TcpServerProperties(
                        true,
                        0,
                        1,
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(1)
                ),
                new FrameDecoder(OBJECT_MAPPER),
                new TelemetryAckEncoder(OBJECT_MAPPER),
                new SimpleMeterRegistry()
        );

        CompletableFuture<Integer> boundPort = new CompletableFuture<>();

        Thread listener = Thread.ofPlatform().start(() -> {
            try {
                server.start(boundPort);
            } catch (IOException exception) {
                boundPort.completeExceptionally(exception);
            }
        });

        try {
            int port = boundPort.get(2, TimeUnit.SECONDS);

            try (
                    Socket client = new Socket(
                            InetAddress.getLoopbackAddress(),
                            port
                    )
            ) {
                client.setSoTimeout(3_000);

                LengthPrefixedFrameCodec.write(
                        OBJECT_MAPPER.writeValueAsBytes(input),
                        client.getOutputStream()
                );

                byte[] payload = LengthPrefixedFrameCodec.read(
                        client.getInputStream()
                );

                return OBJECT_MAPPER.readValue(
                        payload,
                        TelemetryAck.class
                );
            }
        } finally {
            server.close();
            listener.join(2_000);
        }
    }

    private static TelemetryEvent event() {
        return new TelemetryEvent(
                1,
                UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                42,
                Instant.parse("2026-08-01T10:15:30Z"),
                Instant.parse("2026-08-01T10:15:30.083Z"),
                new TelemetryData(
                        72.4,
                        91.8,
                        12.6,
                        85312,
                        41.9028,
                        12.4964
                )
        );
    }

    private static TelemetryMessage message() {
        return new TelemetryMessage(
                ProtocolConstants.PROTOCOL_VERSION,
                UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                42,
                Instant.parse("2026-08-01T10:15:30Z"),
                72.4,
                91.8,
                12.6,
                85312,
                41.9028,
                12.4964
        );
    }
}
