package it.fleetpulse.api.state;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.micrometer.core.instrument.MeterRegistry;
import it.fleetpulse.api.state.persistence.PostgreSqlLatestSampleQuery;
import it.fleetpulse.api.state.redis.RedisLatestStateProjection;
import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import it.fleetpulse.api.vehicle.VehicleRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(VehicleStateRecoveryIntegrationTest.FixedClock.class)
class VehicleStateRecoveryIntegrationTest extends PostgreSqlIntegrationSupport {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant OBSERVED = NOW.minusSeconds(30);
    private static final UUID ID = new UUID(0, 390);
    private static final String KEY = "vehicle:last:" + ID;
    private static final String PATH = "/api/v1/vehicles/" + ID + "/state";
    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.8-alpine")
        .withNetwork(NETWORK).withNetworkAliases("redis").withExposedPorts(6379)
        .withCommand("redis-server", "--save", "", "--appendonly", "no");

    @Container
    static final ToxiproxyContainer TOXIPROXY =
        new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(NETWORK).withNetworkAliases("toxiproxy").dependsOn(REDIS);

    private static Proxy proxy;

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) throws IOException {
        proxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
            .createProxy("redis", "0.0.0.0:8666", "redis:6379");
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> TOXIPROXY.getMappedPort(8666));
        registry.add("spring.data.redis.timeout", () -> "200ms");
        registry.add("spring.data.redis.connect-timeout", () -> "200ms");
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry metrics;
    @Autowired private RedisLatestStateProjection projection;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired private tools.jackson.databind.ObjectMapper json;
    @MockitoSpyBean private PostgreSqlLatestSampleQuery samples;
    @MockitoSpyBean private VehicleRepository vehicles;
    private List<Map<String, Object>> originalSamples;
    private List<Map<String, Object>> originalAlerts;
    private static final Path EVIDENCE = Path.of("target", "fp046-evidence", "faults.jsonl");

    @BeforeAll
    static void prepareEvidence() throws IOException {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, "");
    }

    @BeforeEach
    void fixture() throws Exception {
        reset(samples);
        proxy.enable();
        REDIS.execInContainer("redis-cli", "FLUSHDB");
        awaitConnection();
        jdbc.update("DELETE FROM maintenance_alerts");
        jdbc.update("DELETE FROM telemetry_samples");
        jdbc.update("DELETE FROM vehicles");
        jdbc.update("""
            INSERT INTO vehicles (id, external_code, plate, status, service_interval_km,
                next_service_at_km, created_at)
            VALUES (?, 'RECOVERY', 'FP039', 'DISABLED', 15000, 90000, ?)
            """, ID, Timestamp.from(OBSERVED));
        jdbc.update("""
            INSERT INTO telemetry_samples (message_id, vehicle_id, sequence_number, observed_at,
                received_at, processed_at, speed_kmh, engine_temperature_c, battery_voltage,
                odometer_km, latitude, longitude)
            VALUES (?, ?, 42, ?, ?, ?, 72.4, 91.8, 12.6, 85312, 41.9, 12.4)
            """, UUID.randomUUID(), ID, Timestamp.from(OBSERVED), Timestamp.from(OBSERVED),
            Timestamp.from(OBSERVED));
        originalSamples = jdbc.queryForList("SELECT * FROM telemetry_samples ORDER BY id");
        originalAlerts = jdbc.queryForList("SELECT * FROM maintenance_alerts ORDER BY id");
    }

    @AfterEach
    void cacheFaultsMustNotMutateDomainData() {
        assertThat(jdbc.queryForList("SELECT * FROM telemetry_samples ORDER BY id"))
            .isEqualTo(originalSamples);
        assertThat(jdbc.queryForList("SELECT * FROM maintenance_alerts ORDER BY id"))
            .isEqualTo(originalAlerts);
    }

    @AfterAll
    static void closeNetwork() {
        TOXIPROXY.stop();
        REDIS.stop();
        NETWORK.close();
    }

    @Test
    void controlledLatencyKeepsCacheHitAndRecoversWithoutRestart() throws Exception {
        String response = state();
        double hits = count("hits");
        double fallbacks = count("fallback");
        double failures = count("failures");
        double repairs = count("repair.failures");
        clearInvocations(samples, vehicles);
        var toxic = proxy.toxics().latency("latency", ToxicDirection.DOWNSTREAM, 50);
        long elapsed;
        try {
            long started = System.nanoTime();
            mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
            elapsed = System.nanoTime() - started;
            assertThat(Duration.ofNanos(elapsed)).isBetween(Duration.ofMillis(40), Duration.ofSeconds(2));
            assertThat(count("hits")).isEqualTo(hits + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks);
            assertThat(count("failures")).isEqualTo(failures);
            assertThat(count("repair.failures")).isEqualTo(repairs);
            verifyNoInteractions(samples, vehicles);
        } finally {
            toxic.remove();
        }
        REDIS.execInContainer("redis-cli", "DEL", KEY);
        assertRecovered(response);
        recordFault("latency", Map.of("latencyMs", 50, "jitterMs", 0), elapsed,
            "HTTP 200 cache hit, no fallback; repair and hit after removal");
    }

    @Test
    void tcpResetIsObservedAndStateFallsBackThenRecovers() throws Exception {
        String response = state();
        double failures = count("failures");
        double fallbacks = count("fallback");
        double repairs = count("repair.failures");
        long elapsed;
        try (Socket socket = proxySocket()) {
            var input = new BufferedInputStream(socket.getInputStream());
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
            assertThat(readLine(input)).isEqualTo("+PONG");
            var toxic = proxy.toxics().resetPeer("reset", ToxicDirection.DOWNSTREAM, 0);
            try {
                // Docker Desktop can translate RST to EOF at the host forwarding boundary.
                assertThatThrownBy(() -> {
                    socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.US_ASCII));
                    readLine(input);
                }).isInstanceOf(IOException.class)
                    .isNotInstanceOf(java.net.SocketTimeoutException.class);
                // Verify the actual reset inside the Docker network, bypassing host forwarding.
                var resetProbe = REDIS.execInContainer("redis-cli", "-h", "toxiproxy", "-p", "8666", "PING");
                assertThat(resetProbe.getExitCode()).isNotZero();
                assertThat(resetProbe.getStderr()).containsIgnoringCase("reset");
                long started = System.nanoTime();
                mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
                elapsed = System.nanoTime() - started;
                assertThat(count("failures")).isEqualTo(failures + 1);
                assertThat(count("fallback")).isEqualTo(fallbacks + 1);
                assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            } finally {
                toxic.remove();
            }
        }
        awaitConnection();
        REDIS.execInContainer("redis-cli", "DEL", KEY);
        assertRecovered(response);
        recordFault("reset_peer", Map.of("timeoutMs", 0), elapsed,
            "Live host socket interrupted; in-network probe confirms reset; HTTP 200 fallback and recovery");
    }

    @Test
    void bandwidthLimitSlowsMeasuredTransferAndPreservesStateAndRecovery() throws Exception {
        String response = state();
        String bulkKey = "fp046:bandwidth";
        String payload = "x".repeat(65_536);
        redis.opsForValue().set(bulkKey, payload, Duration.ofMinutes(1));
        long elapsed;
        var toxic = proxy.toxics().bandwidth("bandwidth", ToxicDirection.DOWNSTREAM, 32);
        try {
            // A separate fixture key makes throttling observable without changing domain JSON.
            try (Socket socket = proxySocket()) {
                var input = new BufferedInputStream(socket.getInputStream());
                byte[] command = ("*2\r\n$3\r\nGET\r\n$" + bulkKey.length() + "\r\n" + bulkKey + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII);
                long started = System.nanoTime();
                socket.getOutputStream().write(command);
                assertThat(readLine(input)).isEqualTo("$65536");
                assertThat(new String(input.readNBytes(65_536), StandardCharsets.US_ASCII)).isEqualTo(payload);
                assertThat(input.readNBytes(2)).containsExactly((byte) '\r', (byte) '\n');
                elapsed = System.nanoTime() - started;
                assertThat(Duration.ofNanos(elapsed)).isBetween(Duration.ofSeconds(1), Duration.ofSeconds(8));
            }
            double handled = count("hits") + count("fallback");
            mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
            assertThat(count("hits") + count("fallback")).isEqualTo(handled + 1);
        } finally {
            toxic.remove();
            REDIS.execInContainer("redis-cli", "DEL", bulkKey);
        }
        awaitConnection();
        REDIS.execInContainer("redis-cli", "DEL", KEY);
        assertRecovered(response);
        recordFault("bandwidth", Map.of("rateKBps", 32, "payloadBytes", 65_536), elapsed,
            "Complete bulk transfer throttled; HTTP 200; repair and hit after removal");
    }

    @Test
    void reconnectsRepairsAndServesCacheHitAfterConnectionOutage() throws Exception {
        double failures = count("failures");
        double repairs = count("repair.failures");
        double fallbacks = count("fallback");
        double misses = count("misses");
        proxy.disable();
        String response;
        try {
            response = state();
            assertThat(count("failures")).isEqualTo(failures + 1);
            assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks + 1);
            assertThat(count("misses")).isEqualTo(misses);
            assertThat(redisValue()).isEmpty();
        } finally {
            proxy.enable();
        }
        assertRecovered(response);
    }

    @Test
    void realRedisStopAndRestartFallsBackThenRepairsWithoutRestartingApi() throws Exception {
        String response = state();
        assertThat(redisValue()).contains("lastSequenceNumber");
        long baselineStart = System.nanoTime();
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
        long baselineNanos = System.nanoTime() - baselineStart;
        var originalSamples = jdbc.queryForList("SELECT * FROM telemetry_samples ORDER BY id");
        double failures = count("failures");
        double repairs = count("repair.failures");
        double fallbacks = count("fallback");
        double misses = count("misses");
        String containerId = REDIS.getContainerId();
        clearInvocations(samples, vehicles);
        // GenericContainer.stop() removes the container; keep it and the proxy endpoint intact.
        REDIS.getDockerClient().stopContainerCmd(containerId).withTimeout(2).exec();
        long outageNanos;
        try {
            assertThat(REDIS.getDockerClient().inspectContainerCmd(containerId).exec()
                .getState().getRunning()).isFalse();
            long started = System.nanoTime();
            mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
            outageNanos = System.nanoTime() - started;
            assertThat(count("failures")).isEqualTo(failures + 1);
            assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks + 1);
            assertThat(count("misses")).isEqualTo(misses);
            verify(samples).findByVehicleId(ID);
            assertThat(jdbc.queryForList("SELECT * FROM telemetry_samples ORDER BY id"))
                .isEqualTo(originalSamples);
        } finally {
            REDIS.getDockerClient().startContainerCmd(containerId).exec();
        }
        awaitConnection();
        assertThat(redisValue()).isEmpty();
        assertRecovered(response);
        assertThat(REDIS.getContainerId()).isEqualTo(containerId);
        System.out.printf("FP-045 real Redis restart: cache hit %.1f ms; outage fallback %.1f ms%n",
            baselineNanos / 1_000_000.0, outageNanos / 1_000_000.0);
    }

    @Test
    void realCommandTimeoutFallsBackAndRecoversAfterNetworkFaultIsRemoved() throws Exception {
        state();
        long baselineStart = System.nanoTime();
        state();
        long baselineNanos = System.nanoTime() - baselineStart;
        proxy.toxics().timeout("blackhole", ToxicDirection.DOWNSTREAM, 0);
        double failures = count("failures");
        double repairs = count("repair.failures");
        double fallbacks = count("fallback");
        String response;
        long elapsedNanos;
        try {
            // TCP rimane aperto, ma Redis non può consegnare risposte al client.
            assertThatThrownBy(() -> projection.findByVehicleId(ID))
                .isInstanceOf(LatestStateProjectionException.class)
                .hasCauseInstanceOf(QueryTimeoutException.class);
            long started = System.nanoTime();
            response = state();
            elapsedNanos = System.nanoTime() - started;
            // Generous test budget, not a production latency SLA or a noisy baseline comparison.
            assertThat(Duration.ofNanos(elapsedNanos))
                .isBetween(Duration.ofMillis(150), Duration.ofSeconds(5));
            System.out.printf("FP-045 command timeout: cache hit %.1f ms; fallback %.1f ms%n",
                baselineNanos / 1_000_000.0, elapsedNanos / 1_000_000.0);
            assertThat(count("failures")).isEqualTo(failures + 1);
            assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks + 1);
        } finally {
            proxy.toxics().get("blackhole").remove();
        }
        // Un comando già inviato può essere applicato anche dopo un timeout client.
        awaitConnection();
        REDIS.execInContainer("redis-cli", "DEL", KEY);
        assertRecovered(response);
        recordFault("timeout", Map.of("toxicTimeoutMs", 0, "clientTimeoutMs", 200), elapsedNanos,
            "QueryTimeoutException; HTTP 200 fallback; repair and hit after removal");
    }

    private Socket proxySocket() throws IOException {
        Socket socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(8666)), 3000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private static String readLine(BufferedInputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < 128) {
            int value = input.read();
            if (value == -1) throw new IOException("Unexpected EOF in Redis response");
            if (value == '\n') return line.toString().stripTrailing();
            line.append((char) value);
        }
        throw new IOException("Redis response header exceeds test limit");
    }

    private void recordFault(String fault, Map<String, Object> parameters, long durationNanos,
            String result) throws IOException {
        String entry = json.writeValueAsString(Map.of("fault", fault, "direction", "DOWNSTREAM",
            "parameters", parameters, "durationMs", durationNanos / 1_000_000.0,
            "result", result, "recovered", true));
        Files.writeString(EVIDENCE, entry + System.lineSeparator(), StandardOpenOption.APPEND);
        System.out.println("FP-046 " + entry);
    }

    @Test
    void repairConnectionFailureAfterSuccessfulMissDoesNotChangePostgresResponse()
        throws Exception {
        double failures = count("failures");
        double repairs = count("repair.failures");
        double misses = count("misses");
        double fallbacks = count("fallback");
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            proxy.disable();
            return result;
        }).when(samples).findByVehicleId(any());
        String response;
        try {
            response = state();
            assertThat(count("failures")).isEqualTo(failures);
            assertThat(count("misses")).isEqualTo(misses + 1);
            assertThat(count("repair.failures")).isEqualTo(repairs + 1);
            assertThat(count("fallback")).isEqualTo(fallbacks + 1);
            assertThat(redisValue()).isEmpty();
        } finally {
            reset(samples);
            proxy.enable();
        }
        assertRecovered(response);
    }

    private void assertRecovered(String response) throws Exception {
        awaitConnection();
        double misses = count("misses");
        double fallbacks = count("fallback");
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
        assertThat(count("misses")).isEqualTo(misses + 1);
        assertThat(count("fallback")).isEqualTo(fallbacks + 1);
        assertThat(redisValue()).contains("lastSeenAt", OBSERVED.toString())
            .doesNotContain("stale");
        long ttl = Long.parseLong(REDIS.execInContainer("redis-cli", "PTTL", KEY)
            .getStdout().trim());
        assertThat(ttl).isBetween(1L, 300_000L);
        double hits = count("hits");
        clearInvocations(samples, vehicles);
        mvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(content().json(response));
        assertThat(count("hits")).isEqualTo(hits + 1);
        assertThat(count("fallback")).isEqualTo(fallbacks + 1);
        verifyNoInteractions(samples, vehicles);
    }

    private String state() throws Exception {
        return mvc.perform(get(PATH)).andExpect(status().isOk())
            .andExpect(jsonPath("$.vehicleId").value(ID.toString()))
            .andExpect(jsonPath("$.lastSequenceNumber").value(42))
            .andExpect(jsonPath("$.lastSeenAt").value(OBSERVED.toString()))
            .andExpect(jsonPath("$.stale").value(false))
            .andReturn().getResponse().getContentAsString();
    }

    private void awaitConnection() {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
            .ignoreExceptions().until(() -> projection.findByVehicleId(new UUID(0, 391)).isEmpty());
    }

    private String redisValue() throws Exception {
        return REDIS.execInContainer("redis-cli", "GET", KEY).getStdout().trim();
    }

    private double count(String name) {
        return metrics.get("fleetpulse.api.cache." + name).counter().count();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
