package it.fleetpulse.simulator.tcp;

import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.simulator.tcp.exception.MalformedAcknowledgementException;
import it.fleetpulse.simulator.tcp.exception.UnsupportedProtocolVersionException;

final class TelemetryAcknowledgementValidator {

    private TelemetryAcknowledgementValidator() {
    }

    static void validate(
        TelemetryAck acknowledgement) throws MalformedAcknowledgementException,
        UnsupportedProtocolVersionException {
        if (acknowledgement == null) {
            throw new MalformedAcknowledgementException("Acknowledgement must not be null");
        }
        if (acknowledgement.protocolVersion() != ProtocolConstants.PROTOCOL_VERSION) {
            throw new UnsupportedProtocolVersionException(acknowledgement.protocolVersion());
        }
        if (acknowledgement.messageId() == null) {
            throw new MalformedAcknowledgementException(
                "Acknowledgement messageId must not be null");
        }
        if (acknowledgement.status() == null) {
            throw new MalformedAcknowledgementException("Acknowledgement status must not be null");
        }
        if (acknowledgement.receivedAt() == null) {
            throw new MalformedAcknowledgementException(
                "Acknowledgement receivedAt must not be null");
        }
        if (acknowledgement.status() == AckStatus.ACCEPTED && acknowledgement.errorCode() != null) {
            throw new MalformedAcknowledgementException(
                "ACCEPTED acknowledgement must not contain an errorCode");
        }
        if (acknowledgement.status() == AckStatus.REJECTED && acknowledgement.errorCode() == null) {
            throw new MalformedAcknowledgementException(
                "REJECTED acknowledgement must contain an errorCode");
        }
    }
}
