package it.fleetpulse.gateway.tcp;

import it.fleetpulse.protocol.AckStatus;
import it.fleetpulse.protocol.ProtocolConstants;
import it.fleetpulse.protocol.TelemetryAck;
import it.fleetpulse.protocol.TelemetryMessage;

import java.time.Instant;

final class TestAcknowledgements {
    private TestAcknowledgements() {
    }

    static TelemetryAck accepted(TelemetryMessage message) {
        return new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION,
                message.messageId(),
                AckStatus.ACCEPTED,
                Instant.EPOCH,
                null
        );
    }
}
