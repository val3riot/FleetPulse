package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.TelemetryEventHandler;
import it.fleetpulse.processor.telemetry.TelemetryEventProcessingService;
import it.fleetpulse.processor.telemetry.TelemetrySource;
import it.fleetpulse.processor.telemetry.persistence.PostgreSqlIntegrationSupport;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest
@ActiveProfiles("test")
@Import(TelemetryOffsetSemanticsIntegrationTest.CrashPointConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TelemetryOffsetSemanticsIntegrationTest extends PostgreSqlIntegrationSupport {

    private static final String RAW_TOPIC = "offset-semantics.raw.v1";
    private static final String REJECTED_TOPIC = "offset-semantics.rejected.v1";
    private static final String DEAD_LETTER_TOPIC = "offset-semantics.dead-letter.v1";
    private static final String GROUP_ID = "offset-semantics-test";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CrashPointTelemetryEventHandler crashPointHandler;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = adminClient()) {
            admin.createTopics(List.of(new NewTopic(RAW_TOPIC, 1, (short) 1),
                new NewTopic(REJECTED_TOPIC, 1, (short) 1),
                new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1))).all()
                .get(10, TimeUnit.SECONDS);
        }
    }

    @BeforeEach
    void resetState() {
        crashPointHandler.reset();
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
    void retriesWithoutDataLossWhenFailureOccursBeforeDatabaseCommit() throws Exception {
        TelemetryEvent event = event();
        insertActiveVehicle(event.vehicleId());
        crashPointHandler.pauseOnce(event.messageId(), CrashPoint.BEFORE_DATABASE_COMMIT);

        var sendResult = kafkaTemplate.send(RAW_TOPIC, event.vehicleId().toString(), event)
            .get(10, TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(RAW_TOPIC,
            sendResult.getRecordMetadata().partition());
        long sourceOffset = sendResult.getRecordMetadata().offset();

        try {
            assertThat(crashPointHandler.awaitCrashPoint(Duration.ofSeconds(10))).isTrue();
            assertThat(sampleCount(event.messageId())).isZero();
            assertThat(offsetHasAdvancedPast(partition, sourceOffset)).isFalse();
        } finally {
            crashPointHandler.release();
        }

        awaitSample(event.messageId());
        awaitCommittedOffset(partition, sourceOffset + 1);

        assertThat(crashPointHandler.attemptsFor(event.messageId())).isEqualTo(2);
        assertThat(sampleCount(event.messageId())).isOne();
    }

    @Test
    void replaysIdempotentlyWhenFailureOccursAfterDatabaseCommit() throws Exception {
        TelemetryEvent event = event();
        insertActiveVehicle(event.vehicleId());
        crashPointHandler.pauseOnce(event.messageId(), CrashPoint.AFTER_DATABASE_COMMIT);

        var sendResult = kafkaTemplate.send(RAW_TOPIC, event.vehicleId().toString(), event)
            .get(10, TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(RAW_TOPIC,
            sendResult.getRecordMetadata().partition());
        long sourceOffset = sendResult.getRecordMetadata().offset();

        try {
            assertThat(crashPointHandler.awaitCrashPoint(Duration.ofSeconds(10))).isTrue();
            assertThat(sampleCount(event.messageId())).isOne();
            assertThat(offsetHasAdvancedPast(partition, sourceOffset)).isFalse();
        } finally {
            crashPointHandler.release();
        }

        awaitCommittedOffset(partition, sourceOffset + 1);

        assertThat(crashPointHandler.attemptsFor(event.messageId())).isEqualTo(2);
        assertThat(sampleCount(event.messageId())).isOne();
    }

    @Test
    void doesNotReplayWhenProcessorRestartsAfterOffsetCommit() throws Exception {
        TelemetryEvent event = event();
        insertActiveVehicle(event.vehicleId());

        var sendResult = kafkaTemplate.send(RAW_TOPIC, event.vehicleId().toString(), event)
            .get(10, TimeUnit.SECONDS);
        TopicPartition partition = new TopicPartition(RAW_TOPIC,
            sendResult.getRecordMetadata().partition());
        long sourceOffset = sendResult.getRecordMetadata().offset();

        awaitSample(event.messageId());
        awaitCommittedOffset(partition, sourceOffset + 1);
        assertThat(crashPointHandler.attemptsFor(event.messageId())).isOne();

        listenerRegistry.stop();
        listenerRegistry.start();
        awaitListenerAssignment();

        assertThat(crashPointHandler.attemptsFor(event.messageId())).isOne();
        assertThat(sampleCount(event.messageId())).isOne();
    }

    private void insertActiveVehicle(UUID vehicleId) {
        jdbcTemplate.update("""
                INSERT INTO vehicles (
                    id, external_code, plate, status, service_interval_km,
                    next_service_at_km, created_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?)
                """, vehicleId, "VEHICLE-" + vehicleId,
            vehicleId.toString().substring(0, 8), 15_000, 90_000L,
            OffsetDateTime.now(ZoneOffset.UTC));
    }

    private int sampleCount(UUID messageId) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM telemetry_samples WHERE message_id = ?", Integer.class,
            messageId);
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

    private static boolean offsetHasAdvancedPast(TopicPartition partition,
        long sourceOffset) throws Exception {
        try (AdminClient admin = adminClient()) {
            var committed = admin.listConsumerGroupOffsets(GROUP_ID).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
            return committed != null && committed.offset() > sourceOffset;
        }
    }

    private static void awaitCommittedOffset(TopicPartition partition,
        long expectedOffset) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            if (offsetHasAdvancedPast(partition, expectedOffset - 1)) {
                return;
            }
            Thread.sleep(50);
        }

        fail("Source offset was not committed: " + expectedOffset);
    }

    private void awaitListenerAssignment() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            boolean assigned = listenerRegistry.getListenerContainers().stream()
                .anyMatch(container -> !container.getAssignedPartitions().isEmpty());
            if (assigned) {
                return;
            }
            Thread.sleep(50);
        }

        fail("Kafka listener did not receive a partition assignment after restart");
    }

    private static TelemetryEvent event() {
        UUID vehicleId = UUID.randomUUID();
        Instant receivedAt = Instant.parse("2026-08-17T12:00:00Z");
        return new TelemetryEvent(TelemetryEventVersions.V1, UUID.randomUUID(), vehicleId, 42,
            receivedAt.minusSeconds(1), receivedAt,
            new TelemetryData(72.4, 91.8, 12.6, 85_312, 41.9028, 12.4964));
    }

    private static AdminClient adminClient() {
        return AdminClient.create(
            Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    enum CrashPoint {
        BEFORE_DATABASE_COMMIT,
        AFTER_DATABASE_COMMIT
    }

    @TestConfiguration
    static class CrashPointConfiguration {

        @Bean
        @Primary
        CrashPointTelemetryEventHandler crashPointTelemetryEventHandler(
            TelemetryEventProcessingService delegate) {
            return new CrashPointTelemetryEventHandler(delegate);
        }
    }

    static final class CrashPointTelemetryEventHandler implements TelemetryEventHandler {
        private final TelemetryEventProcessingService delegate;
        private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
        private volatile UUID targetMessageId;
        private volatile CrashPoint crashPoint;
        private volatile CountDownLatch reached = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        CrashPointTelemetryEventHandler(TelemetryEventProcessingService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handle(TelemetryEvent event, TelemetrySource source) {
            int currentAttempt = attempts.merge(event.messageId(), 1, Integer::sum);
            boolean mustCrash = event.messageId().equals(targetMessageId) && currentAttempt == 1;

            if (mustCrash && crashPoint == CrashPoint.BEFORE_DATABASE_COMMIT) {
                reachCrashPointAndFail();
            }

            delegate.handle(event, source);

            if (mustCrash && crashPoint == CrashPoint.AFTER_DATABASE_COMMIT) {
                reachCrashPointAndFail();
            }
        }

        void pauseOnce(UUID messageId, CrashPoint crashPoint) {
            this.targetMessageId = messageId;
            this.crashPoint = crashPoint;
            this.reached = new CountDownLatch(1);
            this.release = new CountDownLatch(1);
        }

        boolean awaitCrashPoint(Duration timeout) throws InterruptedException {
            return reached.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void release() {
            release.countDown();
        }

        int attemptsFor(UUID messageId) {
            return attempts.getOrDefault(messageId, 0);
        }

        void reset() {
            release.countDown();
            attempts.clear();
            targetMessageId = null;
            crashPoint = null;
            reached = new CountDownLatch(0);
            release = new CountDownLatch(0);
        }

        private void reachCrashPointAndFail() {
            reached.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out while simulating processor crash");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while simulating processor crash",
                    exception);
            }
            throw new DataAccessResourceFailureException("simulated processor crash");
        }
    }
}
