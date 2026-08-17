package it.fleetpulse.contracts.telemetry;

import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

public class TelemetryEventJsonContractTest {

    private static final String TOPIC = "telemetry.raw.v1";
    private static final UUID MESSAGE_ID =
        UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deserializesVersionOneFixture() throws IOException {
        TelemetryEvent event;

        try (InputStream input = fixture("telemetry-event-v1.json")) {
            event = objectMapper.readValue(input, TelemetryEvent.class);
        }

        assertThat(event.eventVersion()).isEqualTo(TelemetryEventVersions.V1);
        assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.vehicleId()).isEqualTo(VEHICLE_ID);
        assertThat(event.sequenceNumber()).isEqualTo(42);
        assertThat(event.observedAt()).isEqualTo(Instant.parse("2026-08-01T10:15:30Z"));
        assertThat(event.receivedAt()).isEqualTo(Instant.parse("2026-08-01T10:15:31Z"));
        assertThat(event.telemetry()).isEqualTo(new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964));
    }

    @Test
    void reportsMissingFixtureClearly() {
        assertThatThrownBy(() -> fixture("missing.json"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Missing fixture: missing.json");
    }

    @Test
    void kafkaSerializerProducesVersionOneContract() throws IOException, JSONException {
        JacksonJsonSerializer<TelemetryEvent> serializer = new JacksonJsonSerializer<>();

        try {
            byte[] serialized = serializer.serialize(TOPIC, versionOneEvent());

            JSONAssert.assertEquals(fixtureText("telemetry-event-v1.json"),
                new String(serialized, StandardCharsets.UTF_8), JSONCompareMode.STRICT);
        } finally {
            serializer.close();
        }
    }

    @Test
    void ignoresAdditiveFieldsInVersionOneFixture() throws IOException {
        TelemetryEvent event;

        try (InputStream input = fixture("telemetry-event-v1-additive-fields.json")) {
            event = objectMapper.readValue(input, TelemetryEvent.class);
        }

        assertThat(event).isEqualTo(versionOneEvent());
    }

    @Test
    void kafkaDeserializerIgnoresAdditiveFields() throws IOException {
        JacksonJsonDeserializer<TelemetryEvent> deserializer = new JacksonJsonDeserializer<>();
        deserializer.configure(Map.of(
            JacksonJsonDeserializer.USE_TYPE_INFO_HEADERS, false,
            JacksonJsonDeserializer.VALUE_DEFAULT_TYPE, TelemetryEvent.class.getName(),
            JacksonJsonDeserializer.TRUSTED_PACKAGES, TelemetryEvent.class.getPackageName()
        ), false);

        try {
            TelemetryEvent event = deserializer.deserialize(TOPIC,
                fixtureText("telemetry-event-v1-additive-fields.json")
                    .getBytes(StandardCharsets.UTF_8));

            assertThat(event).isEqualTo(versionOneEvent());
        } finally {
            deserializer.close();
        }
    }

    @Test
    void deserializesUnsupportedVersionWithoutInterpretingItAsValid() throws IOException {
        TelemetryEvent event;

        try (InputStream input = fixture("telemetry-event-v99.json")) {
            event = objectMapper.readValue(input, TelemetryEvent.class);
        }

        assertThat(event.eventVersion()).isEqualTo(99);
        assertThat(event.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(event.vehicleId()).isEqualTo(VEHICLE_ID);
    }

    private InputStream fixture(String name) {
        InputStream input = getClass().getResourceAsStream("/fixtures/" + name);
        if (input == null) {
            throw new IllegalStateException("Missing fixture: " + name);
        }
        return input;
    }

    private String fixtureText(String name) throws IOException {
        try (InputStream input = fixture(name)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static TelemetryEvent versionOneEvent() {
        return new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42,
            Instant.parse("2026-08-01T10:15:30Z"), Instant.parse("2026-08-01T10:15:31Z"),
            new TelemetryData(72.4, 91.8, 12.6, 85312, 41.9028, 12.4964));
    }

}
