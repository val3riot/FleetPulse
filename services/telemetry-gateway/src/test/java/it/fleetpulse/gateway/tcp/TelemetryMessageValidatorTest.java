package it.fleetpulse.gateway.tcp;

import it.fleetpulse.gateway.tcp.exception.InvalidTelemetryException;
import it.fleetpulse.gateway.tcp.exception.MalformedTelemetryException;
import it.fleetpulse.gateway.tcp.exception.UnsupportedProtocolVersionException;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryMessageValidatorTest {

    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-01T10:15:30Z");

    @Test
    void rejectsNullMessage() {
        assertThrows(MalformedTelemetryException.class,
                () -> TelemetryMessageValidator.validate(null));
    }

    @Test
    void rejectsUnsupportedProtocolVersion() {
        assertThrows(UnsupportedProtocolVersionException.class,
                () -> validate(message(2, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                        42, 72.4, 91.8, 12.6, 85312, 41.9, 12.5)));
    }

    @Test
    void rejectsNullRequiredObjectFields() {
        assertThrows(MalformedTelemetryException.class,
                () -> validate(message(1, null, VEHICLE_ID, OBSERVED_AT,
                        42, 72.4, 91.8, 12.6, 85312, 41.9, 12.5)));
        assertThrows(MalformedTelemetryException.class,
                () -> validate(message(1, MESSAGE_ID, null, OBSERVED_AT,
                        42, 72.4, 91.8, 12.6, 85312, 41.9, 12.5)));
        assertThrows(MalformedTelemetryException.class,
                () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, null,
                        42, 72.4, 91.8, 12.6, 85312, 41.9, 12.5)));
    }

    @Test
    void rejectsNegativeSequenceNumberAndOdometer() {
        assertThrows(InvalidTelemetryException.class,
                () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                        -1, 72.4, 91.8, 12.6, 85312, 41.9, 12.5)));
        assertThrows(InvalidTelemetryException.class,
                () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                        42, 72.4, 91.8, 12.6, -1, 41.9, 12.5)));
    }

    @Test
    void rejectsInvalidSpeed() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -0.1}) {
            assertThrows(InvalidTelemetryException.class,
                    () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                            42, value, 91.8, 12.6, 85312, 41.9, 12.5)));
        }
    }

    @Test
    void rejectsNonFiniteEngineTemperature() {
        for (double value : nonFiniteValues()) {
            assertThrows(InvalidTelemetryException.class,
                    () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                            42, 72.4, value, 12.6, 85312, 41.9, 12.5)));
        }
    }

    @Test
    void rejectsInvalidBatteryVoltage() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -0.1}) {
            assertThrows(InvalidTelemetryException.class,
                    () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                            42, 72.4, 91.8, value, 85312, 41.9, 12.5)));
        }
    }

    @Test
    void acceptsCoordinateBoundaries() {
        assertDoesNotThrow(() -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                42, 72.4, 91.8, 12.6, 85312, -90, -180)));
        assertDoesNotThrow(() -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                42, 72.4, 91.8, 12.6, 85312, 90, 180)));
    }

    @Test
    void rejectsOutOfRangeCoordinates() {
        for (double latitude : new double[]{-90.1, 90.1}) {
            assertThrows(InvalidTelemetryException.class,
                    () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                            42, 72.4, 91.8, 12.6, 85312, latitude, 12.5)));
        }
        for (double longitude : new double[]{-180.1, 180.1}) {
            assertThrows(InvalidTelemetryException.class,
                    () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                            42, 72.4, 91.8, 12.6, 85312, 41.9, longitude)));
        }
    }

    @Test
    void rejectsNonFiniteCoordinates() {
        for (double value : nonFiniteValues()) {
            assertThrows(InvalidTelemetryException.class,
                    () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                            42, 72.4, 91.8, 12.6, 85312, value, 12.5)));
            assertThrows(InvalidTelemetryException.class,
                    () -> validate(message(1, MESSAGE_ID, VEHICLE_ID, OBSERVED_AT,
                            42, 72.4, 91.8, 12.6, 85312, 41.9, value)));
        }
    }

    private static double[] nonFiniteValues() {
        return new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
    }

    private static void validate(TelemetryMessage message) throws Exception {
        TelemetryMessageValidator.validate(message);
    }

    private static TelemetryMessage message(int protocolVersion, UUID messageId, UUID vehicleId,
            Instant observedAt, long sequenceNumber, double speedKmh, double engineTemperatureC,
            double batteryVoltage, long odometerKm, double latitude, double longitude) {
        return new TelemetryMessage(protocolVersion, messageId, vehicleId, sequenceNumber, observedAt,
                speedKmh, engineTemperatureC, batteryVoltage, odometerKm, latitude, longitude);
    }
}
