package it.fleetpulse.processor.telemetry.redis;

import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.LatestVehicleState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static it.fleetpulse.processor.telemetry.projection.ProjectionUpdateResult.SKIPPED;
import static it.fleetpulse.processor.telemetry.projection.ProjectionUpdateResult.UPDATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisLatestStateProjectionTest {
    private static final Duration TTL = Duration.ofMinutes(5);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisLatestStateCodec codec = new RedisLatestStateCodec();
    private final LatestVehicleState candidate = new LatestVehicleState(UUID.randomUUID(), 10,
        Instant.parse("2026-08-01T10:15:30Z"), 72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
    private final String key = "vehicle:last:" + candidate.vehicleId();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void executeSessionAgainstMockOperations() {
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(invocation -> {
            SessionCallback<Object> callback = invocation.getArgument(0);
            return callback.execute(redis);
        }).when(redis).execute(any(SessionCallback.class));
    }

    @Test
    void readsUsingExactPrefixAndCanonicalVehicleUuid() {
        var vehicleId = UUID.fromString("97E194A8-64B3-4885-B1E6-25FD482F58C0");

        assertThat(projection(1).findByVehicleId(vehicleId)).isEmpty();

        verify(values).get("vehicle:last:97e194a8-64b3-4885-b1e6-25fd482f58c0");
    }

    @Test
    void conflictRereadsStateAndCanSkipCandidateSupersededByAnotherWriter() {
        var newer = new LatestVehicleState(candidate.vehicleId(), 1,
            candidate.lastSeenAt().plusSeconds(1), 80, 90, 12, 86000, 42, 13);
        when(values.get(key)).thenReturn(null, codec.encode(newer));
        when(redis.exec()).thenReturn(null);

        assertThat(projection(2).updateIfNewer(candidate)).isEqualTo(SKIPPED);

        verify(values, times(2)).get(key);
        verify(redis, times(2)).watch(key);
        verify(redis).exec();
        verify(redis).unwatch();
        verify(values).set(key, codec.encode(candidate), TTL);
    }

    @Test
    void conflictCanBeFollowedBySuccessfulWrite() {
        when(redis.exec()).thenReturn(Collections.emptyList(), List.of(true));

        assertThat(projection(2).updateIfNewer(candidate)).isEqualTo(UPDATED);

        verify(values, times(2)).get(key);
        verify(redis, times(2)).exec();
    }

    @Test
    void exhaustedConflictsAreFailureAndRespectAttemptLimit() {
        when(redis.exec()).thenReturn(null);

        assertThatThrownBy(() -> projection(3).updateIfNewer(candidate))
            .isInstanceOf(LatestStateProjectionException.class)
            .hasMessageContaining("attempts exhausted");

        verify(redis, times(3)).watch(key);
        verify(redis, times(3)).exec();
    }

    @Test
    void singleAttemptDoesNotRetryConflict() {
        when(redis.exec()).thenReturn(null);

        assertThatThrownBy(() -> projection(1).updateIfNewer(candidate))
            .isInstanceOf(LatestStateProjectionException.class);

        verify(redis).exec();
    }

    @Test
    void readFailureIsNotReportedAsCacheMiss() {
        var failure = new DataAccessResourceFailureException("Redis unavailable");
        when(values.get(key)).thenThrow(failure);

        assertThatThrownBy(() -> projection(3).findByVehicleId(candidate.vehicleId()))
            .isInstanceOf(LatestStateProjectionException.class).hasCause(failure);
    }

    @Test
    void failureBeforeMultiUnwatchesAndDoesNotRetry() {
        var failure = new DataAccessResourceFailureException("Read failed");
        when(values.get(key)).thenThrow(failure);

        assertThatThrownBy(() -> projection(3).updateIfNewer(candidate))
            .isInstanceOf(LatestStateProjectionException.class).hasCause(failure);

        verify(redis).watch(key);
        verify(redis).unwatch();
        verify(redis, never()).multi();
        verify(redis, never()).discard();
    }

    @Test
    void invalidStoredJsonUnwatchesWithoutStartingTransaction() {
        when(values.get(key)).thenReturn("{broken");

        assertThatThrownBy(() -> projection(3).updateIfNewer(candidate))
            .isInstanceOf(LatestStateProjectionException.class);

        verify(redis).unwatch();
        verify(redis, never()).multi();
    }

    @Test
    void failureWhileQueuingWriteDiscardsTransaction() {
        var failure = new DataAccessResourceFailureException("Write failed");
        doThrow(failure).when(values).set(key, codec.encode(candidate), TTL);

        assertThatThrownBy(() -> projection(3).updateIfNewer(candidate))
            .isInstanceOf(LatestStateProjectionException.class).hasCause(failure);

        verify(redis).discard();
        verify(redis, never()).exec();
        verify(redis).watch(key);
    }

    @Test
    void execFailurePreservesOriginalCauseEvenIfCleanupAlsoFails() {
        var failure = new DataAccessResourceFailureException("EXEC failed");
        var cleanupFailure = new DataAccessResourceFailureException("DISCARD failed");
        when(redis.exec()).thenThrow(failure);
        doThrow(cleanupFailure).when(redis).discard();

        assertThatThrownBy(() -> projection(3).updateIfNewer(candidate))
            .isInstanceOf(LatestStateProjectionException.class).hasCause(failure);

        assertThat(failure.getSuppressed()).containsExactly(cleanupFailure);
        verify(redis).exec();
        verify(redis).discard();
    }

    private RedisLatestStateProjection projection(int maxAttempts) {
        return new RedisLatestStateProjection(redis, codec,
            new LatestStateProjectionProperties(TTL, maxAttempts));
    }
}
