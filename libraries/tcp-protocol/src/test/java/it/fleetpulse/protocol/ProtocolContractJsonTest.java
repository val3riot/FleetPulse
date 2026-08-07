package it.fleetpulse.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProtocolContractJsonTest {

    private static final UUID MESSAGE_ID = UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-01T10:15:30Z");
    private static final Instant RECEIVED_AT = Instant.parse("2026-08-01T10:15:30.083Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void telemetryMessageMatchesDocumentedPayload() throws Exception {
        TelemetryMessage message = new TelemetryMessage(
                ProtocolConstants.PROTOCOL_VERSION, MESSAGE_ID, VEHICLE_ID, 42, OBSERVED_AT,
                72.4, 91.8, 12.6, 85312, 41.9028, 12.4964
        );

        JsonNode expected = objectMapper.readTree(resource("telemetry-message.json"));

        assertEquals(expected.toString(), objectMapper.valueToTree(message).toString());
        assertEquals(message, objectMapper.treeToValue(expected, TelemetryMessage.class));
    }

    @Test
    void acceptedAckMatchesDocumentedPayload() throws Exception {
        TelemetryAck ack = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION, MESSAGE_ID, AckStatus.ACCEPTED, RECEIVED_AT, null
        );

        JsonNode expected = objectMapper.readTree(resource("telemetry-ack-accepted.json"));
        JsonNode actual = objectMapper.valueToTree(ack);

        assertEquals(expected, actual);
        assertEquals(ack, objectMapper.treeToValue(expected, TelemetryAck.class));
        assertFalse(actual.has("errorCode"));
    }

    @Test
    void rejectedAckMatchesDocumentedPayload() throws Exception {
        TelemetryAck ack = new TelemetryAck(
                ProtocolConstants.PROTOCOL_VERSION, MESSAGE_ID, AckStatus.REJECTED, RECEIVED_AT,
                ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION
        );

        JsonNode expected = objectMapper.readTree(resource("telemetry-ack-rejected.json"));

        assertEquals(expected, objectMapper.valueToTree(ack));
        assertEquals(ack, objectMapper.treeToValue(expected, TelemetryAck.class));
    }

    @Test
    void errorCodeCatalogMatchesProtocolDocumentation() {
        Set<String> actual = Arrays.stream(ProtocolErrorCode.values())
                .map(Enum::name)
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "FRAME_TOO_LARGE",
                "INVALID_FRAME_LENGTH",
                "MALFORMED_PAYLOAD",
                "UNSUPPORTED_PROTOCOL_VERSION",
                "INVALID_TELEMETRY",
                "UPSTREAM_UNAVAILABLE",
                "CAPACITY_LIMIT_REACHED"
        ), actual);
    }

    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
