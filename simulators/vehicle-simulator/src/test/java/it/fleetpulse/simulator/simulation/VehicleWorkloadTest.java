package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleWorkloadTest {

    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void advancesStateOnlyAfterSuccessfulSendAndClosesConnection() {
        RecordingConnection connection = new RecordingConnection();
        List<Long> generatedSequences = new ArrayList<>();
        TelemetryProfile profile = current -> {
            generatedSequences.add(current.sequenceNumber());
            SimulatedVehicleState next = current.next(10, 85, 13.8, 10_000, 41.9, 12.5);
            TelemetryMessage message =
                new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION, UUID.randomUUID(),
                    current.vehicleId(), current.sequenceNumber(),
                    Instant.parse("2026-08-12T12:00:00Z"), 10, 85, 13.8, 10_000, 41.9, 12.5);
            return new TelemetrySample(next, message);
        };
        VehicleWorkload workload = new VehicleWorkload(
            SimulatedVehicleState.initial(new ProvisionedVehicle(VEHICLE_ID, "FP-SIM-001"), 10_000,
                41.9, 12.5), connection, profile, Duration.ofSeconds(1), ignored -> {
            throw new InterruptedException("test completed");
        });

        workload.run();

        assertEquals(List.of(0L), generatedSequences);
        assertEquals(List.of(0L), connection.sentSequences);
        assertTrue(connection.closed);
        assertTrue(Thread.currentThread().isInterrupted());
    }

    private static final class RecordingConnection implements VehicleConnection {
        private final List<Long> sentSequences = new ArrayList<>();
        private boolean connected;
        private boolean closed;

        @Override
        public void connect() {
            connected = true;
        }

        @Override
        public void send(TelemetryMessage message) {
            sentSequences.add(message.sequenceNumber());
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void close() {
            connected = false;
            closed = true;
        }
    }
}
