package it.fleetpulse.gateway.tcp;

import it.fleetpulse.gateway.tcp.exception.InvalidTelemetryException;
import it.fleetpulse.gateway.tcp.exception.MalformedTelemetryException;
import it.fleetpulse.gateway.tcp.exception.UnsupportedProtocolVersionException;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryMessage;
import tools.jackson.databind.JsonNode;

import java.util.List;

final class TelemetryMessageValidator {

    private static final List<String> REQUIRED_NUMERIC_FIELDS = List.of(
            "protocolVersion",
            "sequenceNumber",
            "speedKmh",
            "engineTemperatureC",
            "batteryVoltage",
            "odometerKm",
            "latitude",
            "longitude"
    );

    private TelemetryMessageValidator() {
    }

    static void validateRequiredNumericFields(JsonNode payload) throws MalformedTelemetryException {
        if (payload == null || !payload.isObject()) {
            return;
        }
        for (String field : REQUIRED_NUMERIC_FIELDS) {
            JsonNode value = payload.get(field);
            if (value == null || !value.isNumber()) {
                throw new MalformedTelemetryException(
                        "Telemetry " + field + " must be present and numeric"
                );
            }
        }
    }

    static void validate(TelemetryMessage message) throws InvalidTelemetryException, MalformedTelemetryException, UnsupportedProtocolVersionException {
        if (message == null) {
            throw new MalformedTelemetryException("Telemetry message must not be null");
        }
        if (message.protocolVersion() != ProtocolConstants.PROTOCOL_VERSION) {
            throw new UnsupportedProtocolVersionException(message.protocolVersion());
        }
        if (message.messageId() == null) {
            throw new MalformedTelemetryException("Telemetry messageId must not be null");
        }
        if (message.vehicleId() == null) {
            throw new MalformedTelemetryException("Telemetry vehicleId must not be null");
        }
        if (message.observedAt() == null) {
            throw new MalformedTelemetryException("Telemetry observedAt must not be null");
        }
        if (message.sequenceNumber() < 0) {
            throw new InvalidTelemetryException("sequenceNumber must not be negative");
        }
        if (!Double.isFinite(message.speedKmh()) || message.speedKmh() < 0) {
            throw new InvalidTelemetryException("speedKmh must be finite and not negative");
        }
        if (!Double.isFinite(message.engineTemperatureC())) {
            throw new InvalidTelemetryException("engineTemperatureC must be finite");
        }
        if (!Double.isFinite(message.batteryVoltage()) || message.batteryVoltage() < 0) {
            throw new InvalidTelemetryException("batteryVoltage must be finite and not negative");
        }
        if (message.odometerKm() < 0) {
            throw new InvalidTelemetryException("odometerKm must not be negative");
        }
        if (!Double.isFinite(message.latitude())
                || message.latitude() < -90 || message.latitude() > 90) {
            throw new InvalidTelemetryException("latitude must be between -90 and 90");
        }
        if (!Double.isFinite(message.longitude())
                || message.longitude() < -180 || message.longitude() > 180) {
            throw new InvalidTelemetryException("longitude must be between -180 and 180");
        }
    }
}
