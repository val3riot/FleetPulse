package it.fleetpulse.api.state.redis;

import it.fleetpulse.api.state.LatestStateProjectionException;
import it.fleetpulse.api.state.LatestVehicleState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static it.fleetpulse.api.state.ProjectionUpdateResult.SKIPPED;
import static it.fleetpulse.api.state.ProjectionUpdateResult.UPDATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Testcontainers
class RedisLatestStateProjectionIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2.8-alpine")
        .withExposedPorts(6379);

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-01T10:15:30.123456789Z");
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private final UUID vehicleId = UUID.randomUUID();
    private final RedisLatestStateCodec codec = new RedisLatestStateCodec();

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void returnsEmptyForMissingKey() {
        assertThat(projection().findByVehicleId(vehicleId)).isEmpty();
    }

    @Test
    void createsReadableJsonAtContractKeyWithTtl() {
        var state = state(OBSERVED_AT, Long.MAX_VALUE);

        assertThat(projection().updateIfNewer(state)).isEqualTo(UPDATED);

        assertThat(redis.opsForValue().get(key())).isEqualTo(codec.encode(state));
        assertThat(projection().findByVehicleId(vehicleId)).contains(state);
        assertThat(redis.getExpire(key(), TimeUnit.MILLISECONDS)).isBetween(1L, 30_000L);
    }

    @Test
    void newerTimestampWinsAfterSequenceResetAndRenewsTtl() {
        var projection = projection();
        projection.updateIfNewer(state(OBSERVED_AT, 100));
        redis.expire(key(), Duration.ofSeconds(5));
        var newer = state(OBSERVED_AT.plusNanos(1), 1);

        assertThat(projection.updateIfNewer(newer)).isEqualTo(UPDATED);

        assertThat(projection.findByVehicleId(vehicleId)).contains(newer);
        assertThat(redis.getExpire(key(), TimeUnit.MILLISECONDS)).isGreaterThan(5_000L);
    }

    @Test
    void comparesSequenceExactlyAtEqualTimestamp() {
        var projection = projection();
        projection.updateIfNewer(state(OBSERVED_AT, Long.MAX_VALUE - 1));
        var newer = state(OBSERVED_AT, Long.MAX_VALUE);

        assertThat(projection.updateIfNewer(newer)).isEqualTo(UPDATED);
        assertThat(projection.updateIfNewer(state(OBSERVED_AT, Long.MAX_VALUE - 1)))
            .isEqualTo(SKIPPED);
        assertThat(projection.findByVehicleId(vehicleId)).contains(newer);
    }

    @Test
    void olderAndEquivalentCandidatesDoNotChangeValueOrRenewTtl() {
        var projection = projection();
        var current = state(OBSERVED_AT, 10);
        projection.updateIfNewer(current);
        redis.expire(key(), Duration.ofSeconds(5));
        long remaining = redis.getExpire(key(), TimeUnit.MILLISECONDS);

        assertThat(projection.updateIfNewer(state(OBSERVED_AT.minusNanos(1), 999)))
            .isEqualTo(SKIPPED);
        var equivalentWithDifferentPayload = new LatestVehicleState(vehicleId, 10, OBSERVED_AT,
            99, 99, 14, 999, 0, 0);
        assertThat(projection.updateIfNewer(equivalentWithDifferentPayload)).isEqualTo(SKIPPED);

        assertThat(projection.findByVehicleId(vehicleId)).contains(current);
        assertThat(redis.getExpire(key(), TimeUnit.MILLISECONDS)).isBetween(1L, remaining);
    }

    @Test
    void expiredKeyCanBeInitializedAgain() {
        var projection = new RedisLatestStateProjection(redis, codec,
            new LatestStateProjectionProperties(Duration.ofMillis(150), 3));
        projection.updateIfNewer(state(OBSERVED_AT, 10));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
            assertThat(projection.findByVehicleId(vehicleId)).isEmpty());

        var older = state(OBSERVED_AT.minusSeconds(10), 1);
        assertThat(projection().updateIfNewer(older)).isEqualTo(UPDATED);
        assertThat(projection().findByVehicleId(vehicleId)).contains(older);
    }

    @Test
    void malformedJsonIsReadFailureButCanBeRepaired() {
        redis.opsForValue().set(key(), "{broken");

        assertThatThrownBy(() -> projection().findByVehicleId(vehicleId))
            .isInstanceOf(LatestStateProjectionException.class);
        assertThat(projection().updateIfNewer(state(OBSERVED_AT, 1))).isEqualTo(UPDATED);
        assertThat(projection().findByVehicleId(vehicleId)).contains(state(OBSERVED_AT, 1));
    }

    @Test
    void concurrentWritesKeepGreatestRecency() throws Exception {
        int writers = 12;
        var ready = new CountDownLatch(writers);
        var start = new CountDownLatch(1);
        var projection = projection();

        try (var executor = Executors.newFixedThreadPool(writers)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < writers; i++) {
                var candidate = state(OBSERVED_AT.plusNanos(i), writers - i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent start timed out");
                    }
                    return projection.updateIfNewer(candidate);
                }));
            }
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        assertThat(projection.findByVehicleId(vehicleId))
            .contains(state(OBSERVED_AT.plusNanos(writers - 1), 1));
    }

    private RedisLatestStateProjection projection() {
        return new RedisLatestStateProjection(redis, codec,
            new LatestStateProjectionProperties(Duration.ofSeconds(30), 32));
    }

    private String key() {
        return "vehicle:last:" + vehicleId;
    }

    private LatestVehicleState state(Instant timestamp, long sequence) {
        return new LatestVehicleState(vehicleId, sequence, timestamp,
            72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
    }
}
