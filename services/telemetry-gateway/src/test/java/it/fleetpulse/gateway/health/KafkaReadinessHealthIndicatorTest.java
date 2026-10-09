package it.fleetpulse.gateway.health;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class KafkaReadinessHealthIndicatorTest {
    private final Admin admin = mock(Admin.class);
    private final DescribeTopicsResult result = mock(DescribeTopicsResult.class);

    private KafkaReadinessHealthIndicator indicator(KafkaFuture<Map<String, TopicDescription>> future) {
        when(admin.describeTopics(eq(List.of("raw")), any(DescribeTopicsOptions.class))).thenReturn(result);
        when(result.allTopicNames()).thenReturn(future);
        return new KafkaReadinessHealthIndicator(admin, List.of("raw"), Duration.ofMillis(50));
    }

    @Test
    void checksLeaderAndCachesWithoutProducingOrRepeatedRequests() {
        var node = new Node(1, "private-broker", 9092);
        var topic = new TopicDescription("raw", false,
            List.of(new TopicPartitionInfo(0, node, List.of(node), List.of(node))));
        var check = indicator(KafkaFuture.completedFuture(Map.of("raw", topic)));
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
        verify(admin).describeTopics(eq(List.of("raw")), any(DescribeTopicsOptions.class));
        check.close();
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        verify(admin).close(Duration.ofMillis(100));
    }

    @Test
    void rejectsMissingTopicAndDoesNotExposeDetails() {
        var check = indicator(KafkaFuture.completedFuture(Map.of()));
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(check.health().getDetails()).isEmpty();
    }

    @Test
    void rejectsLeaderlessTopic() {
        var topic = new TopicDescription("raw", false,
            List.of(new TopicPartitionInfo(0, null, List.of(), List.of())));
        assertThat(indicator(KafkaFuture.completedFuture(Map.of("raw", topic)))
            .health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void boundsUnresponsiveBrokerAndSanitizesFailure() {
        var check = indicator(new KafkaFutureImpl<>());
        long start = System.nanoTime();
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(check.health().getDetails()).isEmpty();
    }

    @Test
    void restoresInterruptFlag() {
        var check = indicator(new KafkaFutureImpl<>());
        Thread.currentThread().interrupt();
        try {
            assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void validatesTimeout() {
        assertThatThrownBy(() -> new KafkaReadinessHealthIndicator(admin, List.of("raw"), Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
