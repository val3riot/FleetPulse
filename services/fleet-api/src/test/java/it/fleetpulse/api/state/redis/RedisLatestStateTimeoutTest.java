package it.fleetpulse.api.state.redis;

import it.fleetpulse.api.state.LatestStateProjectionException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RedisLatestStateTimeoutTest {
    @Test
    @SuppressWarnings("unchecked")
    void commandTimeoutIsTranslatedToApplicationFailureForFallback() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        UUID id = UUID.randomUUID();
        when(redis.opsForValue()).thenReturn(values);
        var timeout = new QueryTimeoutException("command timed out");
        when(values.get("vehicle:last:" + id)).thenThrow(timeout);
        var projection = new RedisLatestStateProjection(redis, new RedisLatestStateCodec(),
            new LatestStateProjectionProperties(Duration.ofMinutes(5), 1));
        assertThatThrownBy(() -> projection.findByVehicleId(id))
            .isInstanceOf(LatestStateProjectionException.class).hasCause(timeout);
    }
}
