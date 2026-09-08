package it.fleetpulse.processor.telemetry.redis;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import it.fleetpulse.processor.telemetry.projection.LatestStateProjection;
import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.LatestStateQuery;
import it.fleetpulse.processor.telemetry.projection.LatestVehicleState;
import it.fleetpulse.processor.telemetry.projection.ProjectionUpdateResult;

@Component
public class RedisLatestStateProjection implements LatestStateProjection, LatestStateQuery {

    private final StringRedisTemplate redis;
    private final RedisLatestStateCodec codec;
    private final LatestStateProjectionProperties properties;

    public RedisLatestStateProjection(
            StringRedisTemplate redis,
            RedisLatestStateCodec codec,
            LatestStateProjectionProperties properties) {
        this.redis = Objects.requireNonNull(redis);
        this.codec = Objects.requireNonNull(codec);
        this.properties = Objects.requireNonNull(properties);
    }

    @Override
    public ProjectionUpdateResult updateIfNewer(LatestVehicleState candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(candidate.vehicleId(), "vehicleId must not be null");
        Objects.requireNonNull(candidate.lastSeenAt(), "lastSeenAt must not be null");

        String key = keyFor(candidate.vehicleId());
        String candidateJson = codec.encode(candidate);

        try {
            for (int attempt = 0; attempt < properties.maxAttempts(); attempt++) {
                UpdateAttempt result = attemptUpdate(key, candidateJson, candidate);

                switch (result) {
                    case UPDATED:
                        return ProjectionUpdateResult.UPDATED;
                    case SKIPPED:
                        return ProjectionUpdateResult.SKIPPED;
                    case CONFLICT:
                        break;
                }
            }
        } catch (DataAccessException failure) {
            throw new LatestStateProjectionException(
                    "Cannot update latest state for vehicle " + candidate.vehicleId(),
                    failure);
        }

        throw new LatestStateProjectionException(
                "Latest state update attempts exhausted for vehicle "
                        + candidate.vehicleId());
    }

    @Override
    public Optional<LatestVehicleState> findByVehicleId(UUID vehicleId) {
        Objects.requireNonNull(vehicleId, "vehicleId must not be null");

        try {
            String json = redis.opsForValue().get(keyFor(vehicleId));

            if (json == null) {
                return Optional.empty();
            }

            return Optional.of(codec.decode(json));
        } catch (DataAccessException failure) {
            throw new LatestStateProjectionException(
                    "Cannot read latest state for vehicle " + vehicleId,
                    failure);
        }
    }

    private UpdateAttempt attemptUpdate(
            String key,
            String candidateJson,
            LatestVehicleState candidate) {

        return redis.execute(new SessionCallback<UpdateAttempt>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> UpdateAttempt execute(RedisOperations<K, V> operations) {
                RedisOperations<String, String> session = (RedisOperations<String, String>) operations;

                boolean transactionStarted = false;

                try {
                    session.watch(key);

                    String currentJson = session.opsForValue().get(key);

                    if (currentJson != null) {
                        LatestVehicleState current = codec.decode(currentJson);

                        if (!candidate.isNewerThan(current)) {
                            session.unwatch();
                            return UpdateAttempt.SKIPPED;
                        }
                    }

                    session.multi();
                    transactionStarted = true;

                    session.opsForValue().set(
                            key, candidateJson, properties.ttl());

                    var results = session.exec();
                    transactionStarted = false;

                    if (results == null || results.isEmpty()) {
                        return UpdateAttempt.CONFLICT;
                    }

                    return UpdateAttempt.UPDATED;
                } catch (RuntimeException failure) {
                    try {
                        if (transactionStarted) {
                            session.discard();
                        } else {
                            session.unwatch();
                        }
                    } catch (RuntimeException cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }

                    throw failure;
                }
            }
        });
    }

    private static String keyFor(UUID vehicleId) {
        return "vehicle:last:" + vehicleId;
    }

    private enum UpdateAttempt {
        UPDATED,
        SKIPPED,
        CONFLICT
    }
}
