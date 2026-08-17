package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetrySampleTest {

    @Test
    void acceptsMatchingMessageAndNextState() {
        UUID vehicleId = UUID.randomUUID();
        assertDoesNotThrow(() -> new TelemetrySample(state(vehicleId, 2), message(vehicleId, 1)));
    }

    @Test
    void rejectsMismatchedVehicleOrSequence() {
        UUID vehicleId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
            () -> new TelemetrySample(state(vehicleId, 2), message(UUID.randomUUID(), 1)));
        assertThrows(IllegalArgumentException.class,
            () -> new TelemetrySample(state(vehicleId, 3), message(vehicleId, 1)));
    }

    private static SimulatedVehicleState state(UUID vehicleId, long sequence) {
        return new SimulatedVehicleState(vehicleId, "FP-SIM-001", sequence, 10, 85, 13.8, 10_000,
            41.9, 12.5);
    }

    private static TelemetryMessage message(UUID vehicleId, long sequence) {
        return new TelemetryMessage(ProtocolConstants.PROTOCOL_VERSION, UUID.randomUUID(),
            vehicleId, sequence, Instant.parse("2026-08-12T10:00:00Z"), 10, 85, 13.8, 10_000, 41.9,
            12.5);
    }
}
