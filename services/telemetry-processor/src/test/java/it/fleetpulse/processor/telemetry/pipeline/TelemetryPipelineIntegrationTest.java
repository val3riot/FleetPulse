package it.fleetpulse.processor.telemetry.pipeline;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.gateway.telemetry.PublishingFrameHandler;
import it.fleetpulse.gateway.telemetry.TelemetryEventMapper;
import it.fleetpulse.gateway.telemetry.TelemetryPublishingMetrics;
import it.fleetpulse.gateway.telemetry.kafka.KafkaPublisherProperties;
import it.fleetpulse.gateway.telemetry.kafka.KafkaTelemetryPublisher;
import it.fleetpulse.gateway.tcp.FrameDecoder;
import it.fleetpulse.gateway.tcp.TcpServer;
import it.fleetpulse.gateway.tcp.TcpServerProperties;
import it.fleetpulse.gateway.tcp.TelemetryAckEncoder;
import it.fleetpulse.processor.telemetry.persistence.PostgreSqlIntegrationSupport;
import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TelemetryPipelineIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final String RAW_TOPIC = "pipeline.raw.v1";
    private static final String REJECTED_TOPIC = "pipeline.rejected.v1";
    private static final String DEAD_LETTER_TOPIC = "pipeline.dead-letter.v1";
    private static final String PROCESSOR_GROUP_ID = "pipeline-processor-test";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-17T12:00:00Z");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    private static DefaultKafkaProducerFactory<String, TelemetryEvent> producerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void prepareKafka() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(RAW_TOPIC, 3, (short) 1),
                new NewTopic(REJECTED_TOPIC, 1, (short) 1),
                new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1))).all()
                .get(10, TimeUnit.SECONDS);
        }

        producerFactory = new DefaultKafkaProducerFactory<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true));
    }

    @AfterAll
    static void closeProducer() {
        producerFactory.destroy();
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM telemetry_samples");
        jdbcTemplate.update("DELETE FROM vehicles");
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("fleetpulse.kafka.consumer.group-id", () -> PROCESSOR_GROUP_ID);
        registry.add("fleetpulse.kafka.topics.raw", () -> RAW_TOPIC);
        registry.add("fleetpulse.kafka.topics.rejected", () -> REJECTED_TOPIC);
        registry.add("fleetpulse.kafka.topics.dead-letter", () -> DEAD_LETTER_TOPIC);
    }

    @Test
    void carriesOrderedTelemetryFromTcpGatewayToPostgreSqlAndIgnoresDuplicates()
        throws Exception {
        UUID vehicleId = UUID.randomUUID();
        TelemetryMessage first = message(vehicleId, UUID.randomUUID(), 41);
        TelemetryMessage second = message(vehicleId, UUID.randomUUID(), 42);
        insertActiveVehicle(vehicleId);

        try (KafkaConsumer<String, TelemetryEvent> observer = observer();
             RunningGateway gateway = startGateway()) {
            observer.subscribe(List.of(RAW_TOPIC));
            awaitAssignment(observer);

            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), gateway.port())) {
                client.setSoTimeout(5_000);

                TelemetryAck firstAck = exchange(client, first);
                TelemetryAck secondAck = exchange(client, second);
                TelemetryAck duplicateAck = exchange(client, second);

                assertThat(firstAck.status()).isEqualTo(AckStatus.ACCEPTED);
                assertThat(secondAck.status()).isEqualTo(AckStatus.ACCEPTED);
                assertThat(duplicateAck.status()).isEqualTo(AckStatus.ACCEPTED);
                assertThat(firstAck.messageId()).isEqualTo(first.messageId());
                assertThat(secondAck.messageId()).isEqualTo(second.messageId());
                assertThat(duplicateAck.messageId()).isEqualTo(second.messageId());
            }

            List<ConsumerRecord<String, TelemetryEvent>> records = pollRecords(observer, 3);
            assertKafkaKeyAndOrder(records, vehicleId);
            awaitPersistedSamples(2);

            assertThat(persistedSequenceNumbers()).containsExactly(41L, 42L);
            assertThat(sampleCount(second.messageId())).isOne();
        }
    }

    private static RunningGateway startGateway() throws Exception {
        KafkaTemplate<String, TelemetryEvent> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        var publisher = new KafkaTelemetryPublisher(kafkaTemplate, RAW_TOPIC);
        var handler = new PublishingFrameHandler(
            new TelemetryEventMapper(Clock.fixed(RECEIVED_AT, ZoneOffset.UTC)), publisher,
            new KafkaPublisherProperties(Duration.ofSeconds(5)),
            new TelemetryPublishingMetrics(new SimpleMeterRegistry()));
        return RunningGateway.start(handler);
    }

    private void insertActiveVehicle(UUID vehicleId) {
        jdbcTemplate.update("""
                INSERT INTO vehicles (
                    id, external_code, plate, status, service_interval_km,
                    next_service_at_km, created_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, vehicleId, "PIPELINE-" + vehicleId,
            vehicleId.toString().substring(0, 8), 15_000, 90_000L,
            OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static KafkaConsumer<String, TelemetryEvent> observer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "pipeline-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(properties, new StringDeserializer(),
            new JacksonJsonDeserializer<>(TelemetryEvent.class));
    }

    private static void awaitAssignment(KafkaConsumer<String, TelemetryEvent> consumer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (consumer.assignment().isEmpty() && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(100));
        }
        assertThat(consumer.assignment()).isNotEmpty();
    }

    private static List<ConsumerRecord<String, TelemetryEvent>> pollRecords(
        KafkaConsumer<String, TelemetryEvent> consumer, int expectedCount) {
        List<ConsumerRecord<String, TelemetryEvent>> records = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (records.size() < expectedCount && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
        }

        assertThat(records).hasSize(expectedCount);
        return records;
    }

    private static void assertKafkaKeyAndOrder(List<ConsumerRecord<String, TelemetryEvent>> records,
        UUID vehicleId) {
        assertThat(records).extracting(ConsumerRecord::key)
            .containsOnly(vehicleId.toString());
        assertThat(records).extracting(record -> record.value().sequenceNumber())
            .containsExactly(41L, 42L, 42L);
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst()
            .partition());
        assertThat(records).extracting(ConsumerRecord::offset).isSorted();
    }

    private void awaitPersistedSamples(int expectedCount) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (persistedSampleCount() != expectedCount && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(persistedSampleCount()).isEqualTo(expectedCount);
    }

    private int persistedSampleCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM telemetry_samples", Integer.class);
    }

    private int sampleCount(UUID messageId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM telemetry_samples WHERE message_id = ?", Integer.class,
            messageId);
    }

    private List<Long> persistedSequenceNumbers() {
        return jdbcTemplate.queryForList(
            "SELECT sequence_number FROM telemetry_samples ORDER BY id", Long.class);
    }

    private static TelemetryAck exchange(Socket client, TelemetryMessage message)
        throws IOException {
        LengthPrefixedFrameCodec.write(OBJECT_MAPPER.writeValueAsBytes(message),
            client.getOutputStream());
        byte[] payload = LengthPrefixedFrameCodec.read(client.getInputStream());
        return OBJECT_MAPPER.readValue(payload, TelemetryAck.class);
    }

    private static TelemetryMessage message(UUID vehicleId, UUID messageId, long sequenceNumber) {
        return new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION, messageId, vehicleId,
            sequenceNumber, RECEIVED_AT.minusSeconds(1), 72.4, 91.8, 12.6, 85_312, 41.9028,
            12.4964);
    }

    private static final class RunningGateway implements AutoCloseable {
        private final TcpServer server;
        private final SimpleMeterRegistry registry;
        private final Thread listener;
        private final AtomicReference<Throwable> listenerFailure;
        private final int port;

        private RunningGateway(TcpServer server, SimpleMeterRegistry registry, Thread listener,
            AtomicReference<Throwable> listenerFailure, int port) {
            this.server = server;
            this.registry = registry;
            this.listener = listener;
            this.listenerFailure = listenerFailure;
            this.port = port;
        }

        static RunningGateway start(PublishingFrameHandler handler) throws Exception {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            TcpServer server = new TcpServer(handler,
                new TcpServerProperties(true, 0, 1, Duration.ofSeconds(10),
                    Duration.ofSeconds(2)),
                new FrameDecoder(OBJECT_MAPPER), new TelemetryAckEncoder(OBJECT_MAPPER), registry);
            CompletableFuture<Integer> bindResult = new CompletableFuture<>();
            AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
            Thread listener = Thread.ofPlatform().name("pipeline-gateway-listener").start(() -> {
                try {
                    server.start(bindResult);
                } catch (Throwable exception) {
                    listenerFailure.set(exception);
                    bindResult.completeExceptionally(exception);
                }
            });
            int port = bindResult.get(2, TimeUnit.SECONDS);
            return new RunningGateway(server, registry, listener, listenerFailure, port);
        }

        int port() {
            return port;
        }

        @Override
        public void close() throws Exception {
            server.close();
            listener.join(2_000);
            assertThat(listener.isAlive()).isFalse();
            if (listenerFailure.get() != null) {
                fail("TCP gateway listener failed", listenerFailure.get());
            }
            registry.close();
        }
    }
}
