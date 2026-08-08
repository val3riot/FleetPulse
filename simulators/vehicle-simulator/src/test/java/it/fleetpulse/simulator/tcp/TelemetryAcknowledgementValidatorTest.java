package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.ProtocolErrorCode;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.simulator.tcp.exception.MalformedAcknowledgementException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryAcknowledgementValidatorTest {

    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-01T10:15:30.083Z");

    @Test
    void acceptsDocumentedAcceptedAcknowledgement() {
        TelemetryAck acknowledgement = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION,
                MESSAGE_ID,
                AckStatus.ACCEPTED,
                RECEIVED_AT,
                null
        );

        assertDoesNotThrow(() -> TelemetryAcknowledgementValidator.validate(acknowledgement));
    }

    @Test
    void acceptsDocumentedRejectedAcknowledgement() {
        TelemetryAck acknowledgement = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION,
                MESSAGE_ID,
                AckStatus.REJECTED,
                RECEIVED_AT,
                ProtocolErrorCode.INVALID_TELEMETRY
        );

        assertDoesNotThrow(() -> TelemetryAcknowledgementValidator.validate(acknowledgement));
    }

    @Test
    void acceptedAcknowledgementRejectsErrorCode() {
        TelemetryAck acknowledgement = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION,
                MESSAGE_ID,
                AckStatus.ACCEPTED,
                RECEIVED_AT,
                ProtocolErrorCode.INVALID_TELEMETRY
        );

        assertThrows(
                MalformedAcknowledgementException.class,
                () -> TelemetryAcknowledgementValidator.validate(acknowledgement)
        );
    }

    @Test
    void rejectedAcknowledgementRequiresErrorCode() {
        TelemetryAck acknowledgement = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION,
                MESSAGE_ID,
                AckStatus.REJECTED,
                RECEIVED_AT,
                null
        );

        assertThrows(
                MalformedAcknowledgementException.class,
                () -> TelemetryAcknowledgementValidator.validate(acknowledgement)
        );
    }
}
