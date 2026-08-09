package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContextException;
import tools.jackson.databind.ObjectMapper;

import java.net.BindException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpServerLifecycleTest {

    @Test
    void startReturnsOnlyAfterListenerHasBoundSuccessfully() {
        TcpServerLifecycle lifecycle = new TcpServerLifecycle(server(0));

        lifecycle.start();

        assertTrue(lifecycle.isRunning());
        lifecycle.stop();
        assertFalse(lifecycle.isRunning());
    }

    @Test
    void bindFailureFailsLifecycleStartup() throws Exception {
        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            TcpServerLifecycle lifecycle = new TcpServerLifecycle(
                    server(occupiedPort.getLocalPort())
            );

            ApplicationContextException exception = assertThrows(
                    ApplicationContextException.class,
                    lifecycle::start
            );

            assertInstanceOf(BindException.class, exception.getCause());
            assertFalse(lifecycle.isRunning());
        }
    }

    private static TcpServer server(int port) {
        return new TcpServer(
                message -> { },
                new TcpServerProperties(true, port, 100),
                new FrameDecoder(new ObjectMapper()),
                new SimpleMeterRegistry()
        );
    }
}
