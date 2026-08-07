package it.fleetpulse.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetryAck(
        int protocolVersion,
        UUID messageId,
        AckStatus status,
        Instant receivedAt,
        ProtocolErrorCode errorCode
) {
}
