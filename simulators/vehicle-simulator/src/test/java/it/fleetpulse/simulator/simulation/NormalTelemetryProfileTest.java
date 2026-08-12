package it.fleetpulse.simulator.simulation;

import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalTelemetryProfileTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-12T10:15:30Z");
    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");

    @Test
    void createsMessageFromCurrentSequenceAndAdvancesNextState() {
        SimulatedVehicleState current = state();
        NormalTelemetryProfile profile = profile(42, () -> MESSAGE_ID);

        TelemetrySample sample = profile.next(current);

        assertEquals(ProtocolConstants.PROTOCOL_VERSION, sample.message().protocolVersion());
        assertEquals(MESSAGE_ID, sample.message().messageId());
        assertEquals(current.vehicleId(), sample.message().vehicleId());
        assertEquals(current.sequenceNumber(), sample.message().sequenceNumber());
        assertEquals(OBSERVED_AT, sample.message().observedAt());
        assertEquals(current.sequenceNumber() + 1, sample.nextState().sequenceNumber());
        assertEquals(current.vehicleId(), sample.nextState().vehicleId());
        assertEquals(current.externalCode(), sample.nextState().externalCode());
        assertEquals(7, current.sequenceNumber(), "the current state must remain unchanged");
    }

    @Test
    void producesGradualNominalValuesAndMonotonicOdometer() {
        SimulatedVehicleState current = state();
        NormalTelemetryProfile profile = profile(7, UUID::randomUUID);

        for (int index = 0; index < 1_000; index++) {
            TelemetrySample sample = profile.next(current);
            SimulatedVehicleState next = sample.nextState();

            assertTrue(next.speedKmh() >= 0 && next.speedKmh() <= 130);
            assertTrue(Math.abs(next.speedKmh() - current.speedKmh()) <= 5.0);
            assertTrue(next.engineTemperatureC() >= 75 && next.engineTemperatureC() <= 105);
            assertTrue(Math.abs(next.engineTemperatureC() - current.engineTemperatureC()) <= 1.0);
            assertTrue(next.batteryVoltage() >= 12 && next.batteryVoltage() <= 14.5);
            assertTrue(Math.abs(next.batteryVoltage() - current.batteryVoltage()) <= 0.05);
            assertTrue(next.odometerKm() >= current.odometerKm());
            assertTrue(next.latitude() >= -90 && next.latitude() <= 90);
            assertTrue(next.longitude() >= -180 && next.longitude() <= 180);
            assertEquals((long) Math.floor(next.odometerKm()), sample.message().odometerKm());
            current = next;
        }
    }

    @Test
    void sameSeedAndStateProduceSameEvolution() {
        TelemetrySample first = profile(1234, () -> MESSAGE_ID).next(state());
        TelemetrySample second = profile(1234, () -> MESSAGE_ID).next(state());

        assertEquals(first, second);
    }

    @Test
    void createsANewMessageIdForEachSample() {
        NormalTelemetryProfile profile = profile(42, UUID::randomUUID);

        TelemetrySample first = profile.next(state());
        TelemetrySample second = profile.next(first.nextState());

        assertNotEquals(first.message().messageId(), second.message().messageId());
    }

    @Test
    void rejectsInvalidDependenciesAndInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new NormalTelemetryProfile(Duration.ZERO, clock(), new Random(1), UUID::randomUUID));
        assertThrows(NullPointerException.class,
                () -> new NormalTelemetryProfile(Duration.ofSeconds(1), null, new Random(1), UUID::randomUUID));
        assertThrows(NullPointerException.class,
                () -> new NormalTelemetryProfile(Duration.ofSeconds(1), clock(), new Random(1), () -> null)
                        .next(state()));
    }

    private static NormalTelemetryProfile profile(long seed, MessageIdGenerator messageIdGenerator) {
        return new NormalTelemetryProfile(
                Duration.ofSeconds(1),
                clock(),
                new Random(seed),
                messageIdGenerator
        );
    }

    private static Clock clock() {
        return Clock.fixed(OBSERVED_AT, ZoneOffset.UTC);
    }

    private static SimulatedVehicleState state() {
        return new SimulatedVehicleState(
                UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"),
                "FP-SIM-001",
                7,
                72.0,
                89.0,
                13.8,
                10_000.5,
                41.9028,
                12.4964
        );
    }
}
