package it.fleetpulse.processor.health;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Bounded, read-only topic metadata check. No producer/consumer permissions are inferred. */
public final class KafkaReadinessHealthIndicator implements HealthIndicator, AutoCloseable {
    private final Admin admin;
    private final List<String> topics;
    private final int timeoutMillis;
    private Health cached = Health.outOfService().build();
    private long checkedAt;
    private boolean closed;

    public KafkaReadinessHealthIndicator(Map<String, Object> configuration, List<String> topics,
            Duration timeout) {
        this(createAdmin(configuration, timeout), topics, timeout);
    }

    KafkaReadinessHealthIndicator(Admin admin, List<String> topics, Duration timeout) {
        this.timeoutMillis = checkedTimeout(timeout);
        this.admin = admin;
        this.topics = List.copyOf(topics);
    }

    private static int checkedTimeout(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis < 1 || millis > 2000) {
            throw new IllegalArgumentException("Kafka health timeout must be between 1ms and 2s");
        }
        return (int) millis;
    }

    private static Admin createAdmin(Map<String, Object> configuration, Duration timeout) {
        var settings = new java.util.HashMap<>(configuration);
        int millis = checkedTimeout(timeout);
        settings.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, millis);
        settings.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, millis);
        return Admin.create(settings);
    }

    @Override
    public synchronized Health health() {
        if (closed) {
            return Health.down().build();
        }
        if (checkedAt != 0 && System.nanoTime() - checkedAt < TimeUnit.SECONDS.toNanos(1)) {
            return cached;
        }
        try {
            var descriptions = admin.describeTopics(topics,
                    new DescribeTopicsOptions().timeoutMs(timeoutMillis)).allTopicNames()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            boolean ready = descriptions.keySet().containsAll(topics)
                    && descriptions.values().stream()
                            .allMatch(topic -> !topic.partitions().isEmpty()
                                    && topic.partitions().stream()
                                            .allMatch(partition -> partition.leader() != null
                                                    && partition.leader().id() >= 0));
            cached = ready ? Health.up().build() : Health.down().build();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cached = Health.down().build();
        } catch (Exception failure) {
            // Public operational responses must not expose broker addresses or exceptions.
            cached = Health.down().build();
        }
        checkedAt = System.nanoTime();
        return cached;
    }

    @Override
    public synchronized void close() {
        closed = true;
        admin.close(Duration.ofMillis(100));
    }
}
