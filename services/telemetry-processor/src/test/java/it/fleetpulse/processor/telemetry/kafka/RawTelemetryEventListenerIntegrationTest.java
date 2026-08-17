package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import it.fleetpulse.processor.telemetry.TelemetryEventHandler;
import it.fleetpulse.processor.telemetry.TelemetrySource;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(RawTelemetryEventListenerIntegrationTest.KafkaTestConfiguration.class)
@Testcontainers
public class RawTelemetryEventListenerIntegrationTest {
    private static final String TOPIC = "telemetry.raw.v1";
    private static final String GROUP_ID = "telemetry-processor-integration-test";
    @Autowired
    private KafkaTemplate<String, TelemetryEvent> kafkaTemplate;
    @Autowired
    private BlockingQueue<TelemetryEvent> receivedEvents;
    @Autowired
    private TestTelemetryEventHandler testHandler;
    @Autowired
    private MeterRegistry meterRegistry;

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.3.1");
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
    }

    @BeforeEach
    void clearReceivedEvents() {
        testHandler.reset();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.kafka.bootstrap-servers",
                KAFKA::getBootstrapServers
        );
        registry.add(
                "fleetpulse.kafka.topics.raw",
                () -> TOPIC
        );
    }

    @Test
    void consumesPublishedTelemetryEvent() throws Exception {
        TelemetryEvent expected = event();

        kafkaTemplate.send(
                TOPIC,
                expected.vehicleId().toString(),
                expected
        ).get(10, TimeUnit.SECONDS);

        TelemetryEvent received =
                receivedEvents.poll(10, TimeUnit.SECONDS);

        assertEquals(expected, received);
    }

    @Test
    void preservesOrderForEventsWithSameKey() throws Exception {
        TelemetryEvent first = event(1);
        TelemetryEvent second = event(2);
        String key = first.vehicleId().toString();

        kafkaTemplate.send(TOPIC, key, first)
                .get(10, TimeUnit.SECONDS);

        kafkaTemplate.send(TOPIC, key, second)
                .get(10, TimeUnit.SECONDS);

        TelemetryEvent firstReceived =
                receivedEvents.poll(10, TimeUnit.SECONDS);
        TelemetryEvent secondReceived =
                receivedEvents.poll(10, TimeUnit.SECONDS);

        assertEquals(first, firstReceived);
        assertEquals(second, secondReceived);
    }

    @Test
    void consumersInSameGroupSplitPartitions() {
        try (
                KafkaConsumer<String, String> first =
                        partitionTestConsumer("partition-sharing-test");
                KafkaConsumer<String, String> second =
                        partitionTestConsumer("partition-sharing-test")
        ) {
            first.subscribe(List.of(TOPIC));
            second.subscribe(List.of(TOPIC));

            long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(10);

            while (
                    (first.assignment().isEmpty()
                            || second.assignment().isEmpty())
                            && System.nanoTime() < deadline
            ) {
                first.poll(Duration.ofMillis(200));
                second.poll(Duration.ofMillis(200));
            }

            Set<TopicPartition> firstAssignment =
                    Set.copyOf(first.assignment());
            Set<TopicPartition> secondAssignment =
                    Set.copyOf(second.assignment());

            assertFalse(firstAssignment.isEmpty());
            assertFalse(secondAssignment.isEmpty());
            assertTrue(
                    Collections.disjoint(
                            firstAssignment,
                            secondAssignment
                    )
            );

            Set<TopicPartition> allAssignments =
                    new HashSet<>(firstAssignment);

            allAssignments.addAll(secondAssignment);

            assertEquals(3, allAssignments.size());
        }
    }

    @Test
    void consumersWithDifferentGroupsReceiveEventIndependently()
            throws Exception {
        String suffix = UUID.randomUUID().toString();

        try (
                KafkaConsumer<String, String> first =
                        partitionTestConsumer("independent-a-" + suffix);
                KafkaConsumer<String, String> second =
                        partitionTestConsumer("independent-b-" + suffix)
        ) {
            first.subscribe(List.of(TOPIC));
            second.subscribe(List.of(TOPIC));

            waitForAssignment(first);
            waitForAssignment(second);

            TelemetryEvent event = event();
            String key = event.vehicleId().toString();

            kafkaTemplate.send(TOPIC, key, event)
                    .get(10, TimeUnit.SECONDS);

            assertTrue(receivesKey(first, key));
            assertTrue(receivesKey(second, key));
        }
    }

    @Test
    void commitsOffsetOnlyAfterHandlerCompletes() throws Exception {
        TelemetryEvent event = event();
        testHandler.block(event.messageId());

        var sendResult = kafkaTemplate.send(
                TOPIC,
                event.vehicleId().toString(),
                event
        ).get(10, TimeUnit.SECONDS);

        TopicPartition partition = new TopicPartition(
                sendResult.getRecordMetadata().topic(),
                sendResult.getRecordMetadata().partition()
        );
        long recordOffset = sendResult.getRecordMetadata().offset();

        try {
            assertTrue(testHandler.awaitBlocked(Duration.ofSeconds(10)));
            assertFalse(offsetHasAdvancedPast(partition, recordOffset));
        } finally {
            testHandler.release();
        }

        assertEquals(event, receivedEvents.poll(10, TimeUnit.SECONDS));
        awaitCommittedOffset(partition, recordOffset + 1);
    }

    @Test
    void retriesTemporaryFailureAndThenProcessesEvent()
            throws Exception {
        TelemetryEvent event = event();
        testHandler.failOnce(event.messageId());

        kafkaTemplate.send(
                TOPIC,
                event.vehicleId().toString(),
                event
        ).get(10, TimeUnit.SECONDS);

        assertEquals(
                event,
                receivedEvents.poll(10, TimeUnit.SECONDS)
        );
        assertEquals(
                2,
                testHandler.attemptsFor(event.messageId())
        );
    }

    @Test
    void stopsAfterConfiguredRetryAttempts() throws Exception {
        TelemetryEvent event = event();
        testHandler.alwaysFail(event.messageId());
        double terminalFailuresBefore = meterRegistry.get(
                "fleetpulse.processor.failures.terminal"
        ).counter().count();

        kafkaTemplate.send(
                TOPIC,
                event.vehicleId().toString(),
                event
        ).get(10, TimeUnit.SECONDS);

        awaitMetric(
                "fleetpulse.processor.failures.terminal",
                terminalFailuresBefore + 1
        );

        assertEquals(
                4,
                testHandler.attemptsFor(event.messageId())
        );
        assertNull(receivedEvents.poll(200, TimeUnit.MILLISECONDS));
    }

    private void awaitMetric(
            String metricName,
            double expectedValue
    ) throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            double currentValue = meterRegistry.get(
                    metricName
            ).counter().count();

            if (currentValue >= expectedValue) {
                return;
            }

            Thread.sleep(25);
        }

        fail("Metric did not reach expected value: " + metricName);
    }

    private static boolean offsetHasAdvancedPast(
            TopicPartition partition,
            long recordOffset
    ) throws Exception {
        try (AdminClient admin = adminClient()) {
            var committed = admin.listConsumerGroupOffsets(GROUP_ID)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS)
                    .get(partition);

            return committed != null && committed.offset() > recordOffset;
        }
    }

    private static void awaitCommittedOffset(
            TopicPartition partition,
            long expectedOffset
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            if (offsetHasAdvancedPast(partition, expectedOffset - 1)) {
                return;
            }
            Thread.sleep(50);
        }

        fail("Offset was not committed after handler completion");
    }

    private static AdminClient adminClient() {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        ));
    }

    private static void waitForAssignment(
            KafkaConsumer<String, String> consumer
    ) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        while (
                consumer.assignment().isEmpty()
                        && System.nanoTime() < deadline
        ) {
            consumer.poll(Duration.ofMillis(200));
        }

        assertFalse(consumer.assignment().isEmpty());
    }

    private static boolean receivesKey(
            KafkaConsumer<String, String> consumer,
            String expectedKey
    ) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records =
                    consumer.poll(Duration.ofMillis(200));

            for (ConsumerRecord<String, String> record : records) {
                if (expectedKey.equals(record.key())) {
                    return true;
                }
            }
        }

        return false;
    }

    private static KafkaConsumer<String, String> partitionTestConsumer(
            String groupId
    ) {
        Properties properties = new Properties();

        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        );
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );
        properties.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );

        return new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                new StringDeserializer()
        );
    }


    private static TelemetryEvent event() {
        return event(42);
    }

    private static TelemetryEvent event(long sequenceNumber) {
        return new TelemetryEvent(
                TelemetryEventVersions.V1,
                UUID.randomUUID(),
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                sequenceNumber,
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


    @Configuration
    @EnableKafka
    @Import(KafkaRetryConfiguration.class)
    static class KafkaTestConfiguration {

        @Bean
        KafkaConsumerProperties kafkaConsumerProperties() {
            return new KafkaConsumerProperties(
                    GROUP_ID,
                    3,
                    Duration.ofMillis(10),
                    Duration.ofMillis(50),
                    2.0,
                    0.0
            );
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        ConsumerRecordRecoverer consumerRecordRecoverer() {
            return (record, failure) -> {
                // FP-025 integration tests only retry semantics. FP-026
                // exercises the real recoverer in dedicated tests.
            };
        }

        @Bean
        TestTelemetryEventHandler telemetryEventHandler() {
            return new TestTelemetryEventHandler();
        }

        @Bean
        BlockingQueue<TelemetryEvent> receivedEvents(
                TestTelemetryEventHandler handler
        ) {
            return handler.receivedEvents();
        }

        @Bean
        RawTelemetryEventListener rawTelemetryEventListener(
                TelemetryEventHandler handler
        ) {
            return new RawTelemetryEventListener(handler);
        }

        @Bean
        ConsumerFactory<String, TelemetryEvent> consumerFactory(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
        ) {
            Map<String, Object> properties = Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                    StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                    JacksonJsonDeserializer.class,
                    "spring.json.use.type.headers", false,
                    "spring.json.value.default.type",
                    TelemetryEvent.class.getName(),
                    "spring.json.trusted.packages",
                    TelemetryEvent.class.getPackageName()
            );

            return new DefaultKafkaConsumerFactory<>(properties);
        }
        @Bean
        ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent>
        kafkaListenerContainerFactory(
                ConsumerFactory<String, TelemetryEvent> consumerFactory,
                DefaultErrorHandler errorHandler
        ) {
            ConcurrentKafkaListenerContainerFactory<String, TelemetryEvent> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();

            factory.setConsumerFactory(consumerFactory);
            factory.setCommonErrorHandler(errorHandler);
            factory.getContainerProperties().setAckMode(
                    ContainerProperties.AckMode.RECORD
            );

            return factory;
        }

        @Bean
        ProducerFactory<String, TelemetryEvent> producerFactory(
                @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers
        ) {
            Map<String, Object> properties = Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    JacksonJsonSerializer.class
            );

            return new DefaultKafkaProducerFactory<>(properties);
        }

        @Bean
        KafkaTemplate<String, TelemetryEvent> kafkaTemplate(
                ProducerFactory<String, TelemetryEvent> producerFactory
        ) {
            return new KafkaTemplate<>(producerFactory);
        }
    }

    static final class TestTelemetryEventHandler
            implements TelemetryEventHandler {
        private final BlockingQueue<TelemetryEvent> receivedEvents =
                new LinkedBlockingQueue<>();
        private volatile UUID blockedMessageId;
        private volatile UUID failOnceMessageId;
        private volatile UUID alwaysFailMessageId;
        private final Map<UUID, Integer> attempts =
                new ConcurrentHashMap<>();
        private volatile CountDownLatch entered = new CountDownLatch(0);
        private volatile CountDownLatch release = new CountDownLatch(0);

        @Override
        public void handle(TelemetryEvent event, TelemetrySource source) {
            int currentAttempt = attempts.merge(
                    event.messageId(),
                    1,
                    Integer::sum
            );

            if (
                    event.messageId().equals(failOnceMessageId)
                            && currentAttempt == 1
            ) {
                throw new DataAccessResourceFailureException(
                        "temporary database failure"
                );
            }

            if (event.messageId().equals(alwaysFailMessageId)) {
                throw new DataAccessResourceFailureException(
                        "persistent database failure"
                );
            }

            if (event.messageId().equals(blockedMessageId)) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted while blocking test handler",
                            exception
                    );
                }
            }

            receivedEvents.add(event);
        }

        void block(UUID messageId) {
            blockedMessageId = messageId;
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void failOnce(UUID messageId) {
            failOnceMessageId = messageId;
        }

        void alwaysFail(UUID messageId) {
            alwaysFailMessageId = messageId;
        }

        int attemptsFor(UUID messageId) {
            return attempts.getOrDefault(messageId, 0);
        }

        void reset() {
            receivedEvents.clear();
            attempts.clear();
            blockedMessageId = null;
            failOnceMessageId = null;
            alwaysFailMessageId = null;
            entered = new CountDownLatch(0);
            release = new CountDownLatch(0);
        }

        boolean awaitBlocked(Duration timeout) throws InterruptedException {
            return entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        void release() {
            release.countDown();
            blockedMessageId = null;
        }

        BlockingQueue<TelemetryEvent> receivedEvents() {
            return receivedEvents;
        }
    }
}
