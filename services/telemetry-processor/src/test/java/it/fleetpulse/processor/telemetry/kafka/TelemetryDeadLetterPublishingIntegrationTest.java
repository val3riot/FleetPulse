package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryRejectedEvent;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class TelemetryDeadLetterPublishingIntegrationTest {

    private static final String RAW_TOPIC = "telemetry.raw.v1";
    private static final String REJECTED_TOPIC = "telemetry.rejected.v1";
    private static final String DEAD_LETTER_TOPIC = "telemetry.dead-letter.v1";
    private static final String ORIGINAL_KEY = "vehicle-id";
    private static final Instant FAILED_AT = Instant.parse("2026-08-17T10:00:00Z");
    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    private static DefaultKafkaProducerFactory<String, Object> producerFactory;

    @BeforeAll
    static void createTopicAndProducer() throws Exception {
        try (AdminClient admin = AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(REJECTED_TOPIC, 1, (short) 1),
                new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }

        producerFactory = new DefaultKafkaProducerFactory<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class));
    }

    @Test
    void publishesDomainRejectionWithVehicleKeyAndSourceMetadata() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TelemetryTerminalEventPublisher publisher = publisher();
        TelemetryRejectedEvent event = new TelemetryRejectedEvent(MESSAGE_ID, VEHICLE_ID,
            TelemetryRejectionReason.UNKNOWN_VEHICLE, FAILED_AT, RAW_TOPIC, 2, 84L);

        try (KafkaConsumer<String, String> consumer = consumer("rejected-publishing-test")) {
            TopicPartition partition = new TopicPartition(REJECTED_TOPIC, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));

            publisher.publishRejected(event);

            ConsumerRecord<String, String> published =
                awaitRecord(consumer, "Rejected record not received");
            JsonNode json = objectMapper.readTree(published.value());

            assertThat(published.key()).isEqualTo(VEHICLE_ID.toString());
            assertThat(json.get("messageId").stringValue()).isEqualTo(MESSAGE_ID.toString());
            assertThat(json.get("vehicleId").stringValue()).isEqualTo(VEHICLE_ID.toString());
            assertThat(json.get("reason").stringValue()).isEqualTo("UNKNOWN_VEHICLE");
            assertThat(json.get("rejectedAt").stringValue()).isEqualTo(FAILED_AT.toString());
            assertThat(json.get("sourceTopic").stringValue()).isEqualTo(RAW_TOPIC);
            assertThat(json.get("sourcePartition").asInt()).isEqualTo(2);
            assertThat(json.get("sourceOffset").asLong()).isEqualTo(84L);
        }
    }

    @AfterAll
    static void closeProducer() {
        producerFactory.destroy();
    }

    @Test
    void publishesExhaustedFailureWithSourceMetadataAndPayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        TelemetryTerminalEventPublisher publisher = publisher();
        TelemetryDeadLetterRecoverer recoverer = new TelemetryDeadLetterRecoverer(
            new TelemetryDeadLetterEventFactory(Clock.fixed(FAILED_AT, ZoneOffset.UTC),
                new KafkaOriginalPayloadResolver(objectMapper), new KafkaDeliveryAttemptResolver()),
            publisher);
        ConsumerRecord<String, Map<String, Object>> sourceRecord = sourceRecord();

        try (KafkaConsumer<String, String> consumer = consumer("dead-letter-publishing-test")) {
            TopicPartition deadLetterPartition = new TopicPartition(DEAD_LETTER_TOPIC, 0);
            consumer.assign(List.of(deadLetterPartition));
            consumer.seekToBeginning(List.of(deadLetterPartition));

            recoverer.accept(sourceRecord, new RuntimeException("database unavailable"));

            ConsumerRecord<String, String> published =
                awaitRecord(consumer, "Dead-letter record not received");
            JsonNode json = objectMapper.readTree(published.value());

            assertThat(published.key()).isEqualTo(ORIGINAL_KEY);
            assertThat(json.get("failedAt").stringValue()).isEqualTo(FAILED_AT.toString());
            assertThat(json.get("sourceTopic").stringValue()).isEqualTo(RAW_TOPIC);
            assertThat(json.get("sourcePartition").asInt()).isEqualTo(1);
            assertThat(json.get("sourceOffset").asLong()).isEqualTo(42L);
            assertThat(json.get("attempts").asInt()).isEqualTo(4);
            assertThat(json.get("errorCode").stringValue()).isEqualTo(
                "PROCESSING_RETRIES_EXHAUSTED");
            assertThat(json.get("errorMessage").stringValue()).isEqualTo("database unavailable");
            assertThat(json.get("originalKey").stringValue()).isEqualTo(ORIGINAL_KEY);
            assertThat(json.get("originalPayload").get("messageId").stringValue()).isEqualTo(
                "message-id");
        }
    }

    private static TelemetryTerminalEventPublisher publisher() {
        return new KafkaTelemetryTerminalEventPublisher(new KafkaTemplate<>(producerFactory),
            new KafkaTopicsProperties(RAW_TOPIC, REJECTED_TOPIC, DEAD_LETTER_TOPIC),
            new KafkaTerminalPublishingProperties(Duration.ofSeconds(5)));
    }

    private static ConsumerRecord<String, Map<String, Object>> sourceRecord() {
        ConsumerRecord<String, Map<String, Object>> record =
            new ConsumerRecord<>(RAW_TOPIC, 1, 42L, ORIGINAL_KEY,
                Map.of("messageId", "message-id"));

        record.headers().add(KafkaHeaders.DELIVERY_ATTEMPT,
            ByteBuffer.allocate(Integer.BYTES).putInt(4).array());

        return record;
    }

    private static KafkaConsumer<String, String> consumer(String groupId) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        return new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer());
    }

    private static ConsumerRecord<String, String> awaitRecord(
        KafkaConsumer<String, String> consumer, String failureMessage) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(200));

            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }

        throw new AssertionError(failureMessage);
    }
}
