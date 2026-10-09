package it.fleetpulse.processor.telemetry.kafka;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class TelemetryProcessorRestartIntegrationTest {
    private static final String TOPIC = "processor-restart.raw.v1";
    private static final String GROUP = "processor-restart-test";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine3.23")
            .withDatabaseName("restart_test").withUsername("fleetpulse")
            .withPassword("restart_test");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.8-alpine")
            .withExposedPorts(6379);

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void retainEvidence() throws Exception {
        Path destination = Path.of("target", "fp044-evidence");
        Files.createDirectories(destination);
        try (var files = Files.list(temporaryDirectory)) {
            for (Path file : files.toList()) {
                Files.copy(file, destination.resolve(file.getFileName()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @Test
    void replaysAfterJvmCrashWithoutDuplicatingSampleOrAlertsAndResumesAfterCommittedRestart()
            throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (AdminClient admin = AdminClient
                .create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()));
                KafkaProducer<String, TelemetryEvent> producer = new KafkaProducer<>(
                        Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
                        new StringSerializer(), new JacksonJsonSerializer<>())) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1),
                    new NewTopic("processor-restart.rejected.v1", 1, (short) 1),
                    new NewTopic("processor-restart.dead-letter.v1", 1, (short) 1)))
                    .all().get(15, TimeUnit.SECONDS);
            Process crashed = startProcessor("crash", true);
            try {
                TelemetryEvent event = event(UUID.randomUUID(), 42);
                jdbc.update(
                        """
                                INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                                    next_service_at_km, created_at) VALUES (?, ?, ?, 'ACTIVE', 15000, 90000, now())
                                """,
                        event.vehicleId(), "RESTART-TEST", "RESTART-TEST");
                var record = producer.send(new ProducerRecord<>(TOPIC, 0,
                        event.vehicleId().toString(), event)).get(15, TimeUnit.SECONDS);
                TopicPartition partition = new TopicPartition(TOPIC, record.partition());
                long offset = record.offset();
                assertThat(crashed.waitFor(60, TimeUnit.SECONDS)).as("crash deadline").isTrue();
                assertThat(crashed.exitValue()).withFailMessage("Unexpected child exit: %s",
                        Files.readString(log("crash"))).isEqualTo(44);
                assertThat(committedOffset(admin, partition)).isLessThanOrEqualTo(offset);
                var samples = jdbc.queryForList("SELECT * FROM telemetry_samples ORDER BY id");
                var alerts = jdbc.queryForList("SELECT * FROM maintenance_alerts ORDER BY id");
                assertThat(samples).hasSize(1);
                assertThat(alerts).hasSize(3);
                assertEvidence("crash", event, offset, 1, 1, 0);
                assertThat(Files.readString(log("crash")))
                        .contains("telemetry.event.persisted", event.messageId().toString())
                        .doesNotContain("kafka.telemetry.record.handled");

                Process recovered = startProcessor("recovered", false);
                assertThat(recovered.pid()).isNotEqualTo(crashed.pid());
                try {
                    awaitOffset(admin, partition, offset + 1, recovered);
                    assertEvidence("recovered", event, offset, 1, 0, 1);
                    assertThat(jdbc.queryForList("SELECT * FROM telemetry_samples ORDER BY id"))
                            .isEqualTo(samples);
                    assertThat(jdbc.queryForList("SELECT * FROM maintenance_alerts ORDER BY id"))
                            .isEqualTo(alerts);
                    assertThat(Files.readString(log("recovered")))
                            .contains("duplicate.telemetry.aggregate.ignored",
                                    "kafka.telemetry.record.handled",
                                    event.messageId().toString());
                } finally {
                    stop(recovered);
                }

                Process restarted = startProcessor("committed-restart", false);
                assertThat(restarted.pid()).isNotIn(crashed.pid(), recovered.pid());
                try {
                    TelemetryEvent next = event(event.vehicleId(), 43);
                    var nextRecord = producer.send(new ProducerRecord<>(TOPIC, 0,
                            next.vehicleId().toString(), next)).get(15, TimeUnit.SECONDS);
                    awaitOffset(admin, partition, nextRecord.offset() + 1, restarted);
                    // Processing the next record on the same partition proves progress without
                    // relying on a sleep to assert that the committed record was not replayed.
                    assertEvidence("committed-restart", next, nextRecord.offset(), 1, 1, 0);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_samples",
                            Integer.class)).isEqualTo(2);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maintenance_alerts",
                            Integer.class)).isEqualTo(6);
                    assertThat(Files.readString(log("committed-restart")))
                            .doesNotContain(event.messageId().toString(),
                                    "duplicate.telemetry.aggregate.ignored");
                } finally {
                    stop(restarted);
                }
                assertThat(admin
                        .listOffsets(
                                Map.of(new TopicPartition("processor-restart.dead-letter.v1", 0),
                                        org.apache.kafka.clients.admin.OffsetSpec.latest()))
                        .all().get(10, TimeUnit.SECONDS)
                        .values()).allSatisfy(info -> assertThat(info.offset()).isZero());
            } finally {
                stop(crashed);
            }
        }
    }

    private Process startProcessor(String phase, boolean crash) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
                "-cp",
                System.getProperty("surefire.test.class.path",
                        System.getProperty("java.class.path")),
                RestartTestProcessor.class.getName(),
                "--server.port=0", "--spring.profiles.active=restart-test",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.data.redis.password=",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.properties.session.timeout.ms=6000",
                "--spring.kafka.consumer.properties.heartbeat.interval.ms=1000",
                "--fleetpulse.kafka.consumer.group-id=" + GROUP,
                "--fleetpulse.kafka.topics.raw=" + TOPIC,
                "--fleetpulse.kafka.topics.rejected=processor-restart.rejected.v1",
                "--fleetpulse.kafka.topics.dead-letter=processor-restart.dead-letter.v1",
                "--spring.flyway.enabled=true", "--spring.flyway.locations=classpath:db/migration",
                "--restart-test.crash=" + crash,
                "--restart-test.evidence=" + evidence(phase).toAbsolutePath()));
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log(phase).toFile()).start();
    }

    private Path evidence(String phase) {
        return temporaryDirectory.resolve(phase + ".properties");
    }
    private Path log(String phase) {
        return temporaryDirectory.resolve(phase + ".log");
    }

    private void assertEvidence(String phase, TelemetryEvent event, long offset,
            double attempts, double persisted, double duplicates) throws Exception {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(evidence(phase))) {
            properties.load(input);
        }
        assertThat(properties.getProperty("messageId")).isEqualTo(event.messageId().toString());
        assertThat(properties.getProperty("topic")).isEqualTo(TOPIC);
        assertThat(properties.getProperty("partition")).isEqualTo("0");
        assertThat(properties.getProperty("offset")).isEqualTo(Long.toString(offset));
        assertThat(Double.parseDouble(properties.getProperty("events"))).isEqualTo(attempts);
        assertThat(Double.parseDouble(properties.getProperty("persisted"))).isEqualTo(persisted);
        assertThat(Double.parseDouble(properties.getProperty("duplicates"))).isEqualTo(duplicates);
    }

    private static long committedOffset(AdminClient admin, TopicPartition partition)
            throws Exception {
        var value = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
        return value == null ? -1 : value.offset();
    }

    private static void awaitOffset(AdminClient admin, TopicPartition partition, long expected,
            Process process) {
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(process.isAlive()).as("processor must remain alive").isTrue();
            assertThat(committedOffset(admin, partition)).isEqualTo(expected);
        });
    }

    private static void stop(Process process) throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    private static TelemetryEvent event(UUID vehicleId, long sequence) {
        Instant now = Instant.now();
        return new TelemetryEvent(1, UUID.randomUUID(), vehicleId, sequence, now.minusSeconds(1),
                now,
                new TelemetryData(72.4, 120, 11, 95000, 41.9028, 12.4964));
    }
}
