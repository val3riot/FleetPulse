package it.fleetpulse.processor.health;

import it.fleetpulse.processor.telemetry.kafka.RawTelemetryEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConsumerReadinessTest {
    @Test
    void requiresExistingRunningListenerWithoutRequiringAssignedPartitions() {
        var registry = mock(KafkaListenerEndpointRegistry.class);
        var check = new ReadinessConfiguration().consumerHealthIndicator(registry);
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        var container = mock(MessageListenerContainer.class);
        when(registry.getListenerContainer(RawTelemetryEventListener.LISTENER_ID))
                .thenReturn(container);
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        when(container.isRunning()).thenReturn(true);
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
        verify(container, never()).getAssignedPartitions();
    }
}
