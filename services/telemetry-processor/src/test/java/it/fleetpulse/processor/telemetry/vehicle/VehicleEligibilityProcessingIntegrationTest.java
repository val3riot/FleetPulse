package it.fleetpulse.processor.telemetry.vehicle;

import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.contracts.telemetry.TelemetryRejectionReason;
import it.fleetpulse.processor.telemetry.persistence.PostgreSqlIntegrationSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VehicleEligibilityProcessingIntegrationTest
        extends PostgreSqlIntegrationSupport {

    private static final String RAW_TOPIC = "eligibility.raw.v1";
    private static final String REJECTED_TOPIC = "eligibility.rejected.v1";
    private static final String DEAD_LETTER_TOPIC = "eligibility.dead-letter.v1";
    private static final String GROUP_ID = "eligibility-processing-test";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = adminClient()) {
            admin.createTopics(List.of(
                    new NewTopic(RAW_TOPIC, 1, (short) 1),
                    new NewTopic(REJECTED_TOPIC, 1, (short) 1),
                    new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1)
            )).all().get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        jdbcTemplate.update("DELETE FROM telemetry_samples");
        jdbcTemplate.update("DELETE FROM vehicles");
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("fleetpulse.kafka.consumer.group-id", () -> GROUP_ID);
        registry.add("fleetpulse.kafka.consumer.retry-initial-backoff", () -> "25ms");
        registry.add("fleetpulse.kafka.consumer.retry-max-backoff", () -> "50ms");
        registry.add("fleetpulse.kafka.consumer.retry-jitter-ratio", () -> "0");
        registry.add("fleetpulse.kafka.topics.raw", () -> RAW_TOPIC);
        registry.add("fleetpulse.kafka.topics.rejected", () -> REJECTED_TOPIC);
        registry.add("fleetpulse.kafka.topics.dead-letter", () -> DEAD_LETTER_TOPIC);
    }

    @Test
    void persistsTelemetryForActiveVehicle() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        TelemetryEvent event = event(vehicleId);
        insertVehicle(vehicleId, "ACTIVE");

        kafkaTemplate.send(RAW_TOPIC, vehicleId.toString(), event)
                .get(10, TimeUnit.SECONDS);

        awaitSample(event.messageId());

        assertThat(sampleCount(event.messageId())).isOne();
    }

    @Test
    void rejectsTelemetryForDisabledVehicleAndCommitsSourceOffset() throws Exception {
        UUID vehicleId = UUID.randomUUID();
        TelemetryEvent event = event(vehicleId);
        insertVehicle(vehicleId, "DISABLED");

        assertRejected(event, TelemetryRejectionReason.VEHICLE_DISABLED);
    }

    @Test
    void rejectsTelemetryForUnknownVehicleAndRecordsMetric() throws Exception {
        TelemetryEvent event = event(UUID.randomUUID());
        double before = rejectionCount(TelemetryRejectionReason.UNKNOWN_VEHICLE);

        assertRejected(event, TelemetryRejectionReason.UNKNOWN_VEHICLE);

        assertThat(rejectionCount(TelemetryRejectionReason.UNKNOWN_VEHICLE))
                .isEqualTo(before + 1);
    }

    private void assertRejected(TelemetryEvent event, TelemetryRejectionReason reason)
            throws Exception {
        try (KafkaConsumer<String, String> consumer = rejectedConsumer()) {
            TopicPartition rejectedPartition = new TopicPartition(REJECTED_TOPIC, 0);
            consumer.assign(List.of(rejectedPartition));
            consumer.seekToBeginning(List.of(rejectedPartition));

            var sendResult = kafkaTemplate.send(
                    RAW_TOPIC,
                    event.vehicleId().toString(),
                    event
            ).get(10, TimeUnit.SECONDS);
            TopicPartition sourcePartition = new TopicPartition(
                    RAW_TOPIC,
                    sendResult.getRecordMetadata().partition()
            );

            ConsumerRecord<String, String> rejected = awaitRejected(consumer, event.messageId());
            JsonNode json = objectMapper.readTree(rejected.value());

            assertThat(rejected.key()).isEqualTo(event.vehicleId().toString());
            assertThat(json.get("reason").stringValue()).isEqualTo(reason.name());
            assertThat(json.get("sourceTopic").stringValue()).isEqualTo(RAW_TOPIC);
            assertThat(json.get("sourcePartition").asInt()).isEqualTo(sourcePartition.partition());
            assertThat(json.get("sourceOffset").asLong())
                    .isEqualTo(sendResult.getRecordMetadata().offset());
            assertThat(sampleCount(event.messageId())).isZero();

            awaitCommittedOffset(sourcePartition, sendResult.getRecordMetadata().offset() + 1);
        }
    }

    private ConsumerRecord<String, String> awaitRejected(
            KafkaConsumer<String, String> consumer,
            UUID messageId
    ) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                JsonNode json = objectMapper.readTree(record.value());
                if (messageId.toString().equals(json.get("messageId").stringValue())) {
                    return record;
                }
            }
        }

        return fail("Rejected event not received: " + messageId);
    }

    private void awaitSample(UUID messageId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            if (sampleCount(messageId) == 1) {
                return;
            }
            Thread.sleep(25);
        }

        fail("Telemetry sample not persisted: " + messageId);
    }

    private static void awaitCommittedOffset(TopicPartition partition, long expectedOffset)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            try (AdminClient admin = adminClient()) {
                var committed = admin.listConsumerGroupOffsets(GROUP_ID)
                        .partitionsToOffsetAndMetadata()
                        .get(10, TimeUnit.SECONDS)
                        .get(partition);
                if (committed != null && committed.offset() >= expectedOffset) {
                    return;
                }
            }
            Thread.sleep(50);
        }

        fail("Source offset was not committed: " + expectedOffset);
    }

    private void insertVehicle(UUID vehicleId, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO vehicles (
                    id, external_code, plate, status, service_interval_km,
                    next_service_at_km, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                vehicleId,
                "VEHICLE-" + vehicleId,
                vehicleId.toString().substring(0, 8),
                status,
                15_000,
                90_000L,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private int sampleCount(UUID messageId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM telemetry_samples WHERE message_id = ?",
                Integer.class,
                messageId
        );
    }

    private double rejectionCount(TelemetryRejectionReason reason) {
        return meterRegistry.get("fleetpulse.processor.rejections")
                .tag("reason", reason.name())
                .counter()
                .count();
    }

    private static TelemetryEvent event(UUID vehicleId) {
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        return new TelemetryEvent(
                TelemetryEventVersions.V1,
                UUID.randomUUID(),
                vehicleId,
                42,
                now.minusSeconds(1),
                now,
                new TelemetryData(72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964)
        );
    }

    private static KafkaConsumer<String, String> rejectedConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "rejected-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer());
    }

    private static AdminClient adminClient() {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        ));
    }
}
