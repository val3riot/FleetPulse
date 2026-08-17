package it.fleetpulse.processor.telemetry.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.processor.telemetry.TelemetryEventHandler;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(
        TelemetryPoisonPillIntegrationTest.KafkaTestConfiguration.class
)
@TestPropertySource(properties =
        "fleetpulse.kafka.topics.raw=telemetry.poison.raw.v1"
)
@Testcontainers
class TelemetryPoisonPillIntegrationTest {

    private static final String RAW_TOPIC =
            "telemetry.poison.raw.v1";
    private static final String REJECTED_TOPIC =
            "telemetry.poison.rejected.v1";
    private static final String DEAD_LETTER_TOPIC =
            "telemetry.poison.dead-letter.v1";
    private static final String ORIGINAL_KEY = "vehicle-id";
    private static final Instant FAILED_AT =
            Instant.parse("2026-08-17T10:00:00Z");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka:4.3.1");

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        ))) {
            admin.createTopics(List.of(
                    new NewTopic(RAW_TOPIC, 1, (short) 1),
                    new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1)
            )).all().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void publishesUndeserializablePayloadToDeadLetter()
            throws Exception {
        byte[] malformedPayload =
                "{\"eventVersion\":".getBytes(
                        StandardCharsets.UTF_8
                );

        try (
                KafkaConsumer<String, String> deadLetterConsumer =
                        deadLetterConsumer();
                KafkaProducer<String, byte[]> rawProducer =
                        rawProducer()
        ) {
            TopicPartition deadLetterPartition =
                    new TopicPartition(DEAD_LETTER_TOPIC, 0);
            deadLetterConsumer.assign(List.of(deadLetterPartition));
            deadLetterConsumer.seekToBeginning(
                    List.of(deadLetterPartition)
            );

            rawProducer.send(new ProducerRecord<>(
                    RAW_TOPIC,
                    ORIGINAL_KEY,
                    malformedPayload
            )).get(10, TimeUnit.SECONDS);

            ConsumerRecord<String, String> published =
                    awaitRecord(deadLetterConsumer);
            JsonNode json = new ObjectMapper().readTree(
                    published.value()
            );

            assertThat(published.key()).isEqualTo(ORIGINAL_KEY);
            assertThat(json.get("sourceTopic").stringValue())
                    .isEqualTo(RAW_TOPIC);
            assertThat(json.get("sourcePartition").asInt())
                    .isZero();
            assertThat(json.get("sourceOffset").asLong())
                    .isZero();
            assertThat(json.get("attempts").asInt())
                    .isEqualTo(1);
            assertThat(json.get("errorCode").stringValue())
                    .isEqualTo("DESERIALIZATION_FAILED");
            assertThat(json.get("originalPayload")
                    .get("rawBase64").stringValue())
                    .isEqualTo(
                            Base64.getEncoder().encodeToString(
                                    malformedPayload
                            )
                    );
        }
    }

    private static KafkaProducer<String, byte[]> rawProducer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                ByteArraySerializer.class
        ));
    }

    private static KafkaConsumer<String, String>
    deadLetterConsumer() {
        Properties properties = new Properties();
        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers()
        );
        properties.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                "poison-pill-dlt-test"
        );
        properties.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );

        return new KafkaConsumer<>(
                properties,
                new StringDeserializer(),
                new StringDeserializer()
        );
    }

    private static ConsumerRecord<String, String> awaitRecord(
            KafkaConsumer<String, String> consumer
    ) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records =
                    consumer.poll(Duration.ofMillis(200));

            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }

        throw new AssertionError("Poison-pill DLT not received");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableKafka
    @Import({
            KafkaRetryConfiguration.class,
            KafkaTerminalPublishingConfiguration.class
    })
    static class KafkaTestConfiguration {

        @Bean
        KafkaConsumerProperties kafkaConsumerProperties() {
            return new KafkaConsumerProperties(
                    "poison-pill-processor-test",
                    3,
                    Duration.ofMillis(10),
                    Duration.ofMillis(50),
                    2.0,
                    0.0
            );
        }

        @Bean
        KafkaTerminalPublishingProperties
        terminalPublishingProperties() {
            return new KafkaTerminalPublishingProperties(
                    Duration.ofSeconds(5)
            );
        }

        @Bean
        KafkaTopicsProperties kafkaTopicsProperties() {
            return new KafkaTopicsProperties(
                    RAW_TOPIC,
                    REJECTED_TOPIC,
                    DEAD_LETTER_TOPIC
            );
        }

        @Bean
        Clock clock() {
            return Clock.fixed(FAILED_AT, ZoneOffset.UTC);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        TelemetryEventHandler telemetryEventHandler() {
            return (event, source) -> {
                throw new AssertionError(
                        "Poison pill must not reach the handler"
                );
            };
        }

        @Bean
        RawTelemetryEventListener rawTelemetryEventListener(
                TelemetryEventHandler handler
        ) {
            return new RawTelemetryEventListener(handler);
        }

        @Bean
        ConsumerFactory<String, TelemetryEvent> consumerFactory() {
            Map<String, Object> properties = Map.ofEntries(
                    Map.entry(
                            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                            KAFKA.getBootstrapServers()
                    ),
                    Map.entry(
                            ConsumerConfig.GROUP_ID_CONFIG,
                            "poison-pill-processor-test"
                    ),
                    Map.entry(
                            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                            false
                    ),
                    Map.entry(
                            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                            "earliest"
                    ),
                    Map.entry(
                            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                            ErrorHandlingDeserializer.class
                    ),
                    Map.entry(
                            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                            ErrorHandlingDeserializer.class
                    ),
                    Map.entry(
                            ErrorHandlingDeserializer
                                    .KEY_DESERIALIZER_CLASS,
                            StringDeserializer.class
                    ),
                    Map.entry(
                            ErrorHandlingDeserializer
                                    .VALUE_DESERIALIZER_CLASS,
                            JacksonJsonDeserializer.class
                    ),
                    Map.entry(
                            JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS,
                            false
                    ),
                    Map.entry(
                            JacksonJsonDeserializer.VALUE_DEFAULT_TYPE,
                            TelemetryEvent.class.getName()
                    ),
                    Map.entry(
                            JacksonJsonDeserializer.TRUSTED_PACKAGES,
                            TelemetryEvent.class.getPackageName()
                    )
            );

            return new DefaultKafkaConsumerFactory<>(properties);
        }

        @Bean
        ConcurrentKafkaListenerContainerFactory<
                String,
                TelemetryEvent
        > kafkaListenerContainerFactory(
                ConsumerFactory<String, TelemetryEvent> consumerFactory,
                DefaultErrorHandler errorHandler
        ) {
            ConcurrentKafkaListenerContainerFactory<
                    String,
                    TelemetryEvent
            > factory = new ConcurrentKafkaListenerContainerFactory<>();

            factory.setConsumerFactory(consumerFactory);
            factory.setCommonErrorHandler(errorHandler);
            factory.getContainerProperties().setAckMode(
                    ContainerProperties.AckMode.RECORD
            );

            return factory;
        }

        @Bean
        DefaultKafkaProducerFactory<String, Object>
        producerFactory() {
            return new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    KAFKA.getBootstrapServers(),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                    StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                    JacksonJsonSerializer.class
            ));
        }

        @Bean
        KafkaTemplate<String, Object> kafkaTemplate(
                DefaultKafkaProducerFactory<
                        String,
                        Object
                > producerFactory
        ) {
            return new KafkaTemplate<>(producerFactory);
        }
    }
}
