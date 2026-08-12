package it.fleetpulse.simulator.simulation.reconnect;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.simulator.config.ReconnectProperties;
import it.fleetpulse.simulator.simulation.VehicleConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectingVehicleConnectionTest {

    private static final ReconnectProperties PROPERTIES = new ReconnectProperties(
            Duration.ofMillis(250),
            Duration.ofSeconds(1),
            4,
            0
    );
    private static final TelemetryMessage MESSAGE = new TelemetryMessage(
            ProtocolConstants.PROTOCOL_VERSION,
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            Instant.parse("2026-08-12T12:00:00Z"),
            50,
            90,
            13,
            10_001,
            41.9,
            12.5
    );

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void retriesConnectionWithBoundedExponentialBackoff() throws Exception {
        StubConnection delegate = new StubConnection(3);
        RecordingSleeper sleeper = new RecordingSleeper();
        ReconnectingVehicleConnection connection = connection(delegate, sleeper);

        connection.connect();

        assertTrue(connection.isConnected());
        assertEquals(4, delegate.connectAttempts);
        assertEquals(
                List.of(
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(1)
                ),
                sleeper.delays
        );
    }

    @Test
    void successfulRecoveryResetsBackoffForTheNextOutage() throws Exception {
        StubConnection delegate = new StubConnection(2);
        RecordingSleeper sleeper = new RecordingSleeper();
        ReconnectingVehicleConnection connection = connection(delegate, sleeper);

        connection.connect();
        delegate.connected = false;
        delegate.failuresRemaining = 1;
        connection.connect();

        assertEquals(
                List.of(
                        Duration.ofMillis(250),
                        Duration.ofMillis(500),
                        Duration.ofMillis(250)
                ),
                sleeper.delays
        );
    }

    @Test
    void nextSendReconnectsAfterAConnectionLossWithoutRetryingFailedMessage() throws Exception {
        StubConnection delegate = new StubConnection(0);
        RecordingSleeper sleeper = new RecordingSleeper();
        ReconnectingVehicleConnection connection = connection(delegate, sleeper);
        connection.connect();
        delegate.sendFailure = new IOException("connection reset");

        IOException failure = assertThrows(IOException.class, () -> connection.send(MESSAGE));

        assertEquals("connection reset", failure.getMessage());
        assertFalse(connection.isConnected());
        assertEquals(1, delegate.sendAttempts);

        delegate.sendFailure = null;
        connection.send(MESSAGE);

        assertTrue(connection.isConnected());
        assertEquals(2, delegate.connectAttempts);
        assertEquals(2, delegate.sendAttempts);
    }

    @Test
    void interruptionStopsRetriesAndPreservesInterruptStatus() {
        IOException connectionFailure = new IOException("gateway unavailable");
        StubConnection delegate = new StubConnection(Integer.MAX_VALUE);
        RetrySleeper sleeper = ignored -> {
            throw new InterruptedException("shutdown");
        };
        ReconnectingVehicleConnection connection = connection(delegate, sleeper);

        InterruptedIOException failure = assertThrows(
                InterruptedIOException.class,
                connection::connect
        );

        assertTrue(Thread.currentThread().isInterrupted());
        assertSame(connectionFailure.getClass(), failure.getCause().getClass());
        assertEquals(1, delegate.connectAttempts);
    }

    @Test
    void stopsAfterConfiguredMaximumAttemptsWithoutSleepingAgain() {
        StubConnection delegate = new StubConnection(Integer.MAX_VALUE);
        RecordingSleeper sleeper = new RecordingSleeper();
        ReconnectingVehicleConnection connection = connection(delegate, sleeper);

        IOException failure = assertThrows(IOException.class, connection::connect);

        assertEquals("gateway unavailable", failure.getMessage());
        assertEquals(4, delegate.connectAttempts);
        assertEquals(
                List.of(Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1)),
                sleeper.delays
        );
    }

    private static ReconnectingVehicleConnection connection(
            StubConnection delegate,
            RetrySleeper sleeper
    ) {
        return new ReconnectingVehicleConnection("FP-SIM-001", delegate, PROPERTIES, sleeper);
    }

    private static final class RecordingSleeper implements RetrySleeper {
        private final List<Duration> delays = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            delays.add(duration);
        }
    }

    private static final class StubConnection implements VehicleConnection {
        private int failuresRemaining;
        private int connectAttempts;
        private int sendAttempts;
        private boolean connected;
        private IOException sendFailure;

        private StubConnection(int failuresRemaining) {
            this.failuresRemaining = failuresRemaining;
        }

        @Override
        public void connect() throws IOException {
            connectAttempts++;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IOException("gateway unavailable");
            }
            connected = true;
        }

        @Override
        public void send(TelemetryMessage message) throws IOException {
            sendAttempts++;
            if (sendFailure != null) {
                throw sendFailure;
            }
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            connected = false;
        }
    }
}
