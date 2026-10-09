package it.fleetpulse.gateway.health;

import it.fleetpulse.gateway.tcp.TcpServerLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TcpReadinessTest {
    @Test void requiresPresentRunningTcpListener() {
        @SuppressWarnings("unchecked")
        ObjectProvider<TcpServerLifecycle> provider = mock(ObjectProvider.class);
        var check = new ReadinessConfiguration().tcpHealthIndicator(provider);
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        var tcp = mock(TcpServerLifecycle.class);
        when(provider.getIfAvailable()).thenReturn(tcp);
        assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        when(tcp.isRunning()).thenReturn(true);
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
    }
}
