package it.fleetpulse.processor.telemetry.redis;

import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.LatestVehicleState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisLatestStateCodecTest {
    private static final String JSON = """
        {
          "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
          "lastSequenceNumber": 9223372036854775807,
          "lastSeenAt": "2026-08-01T10:15:30.123456789Z",
          "speedKmh": 72.4,
          "engineTemperatureC": 91.8,
          "batteryVoltage": 12.6,
          "odometerKm": 85312,
          "latitude": 41.9028,
          "longitude": 12.4964
        }
        """;

    private final RedisLatestStateCodec codec = new RedisLatestStateCodec();

    @Test
    void writesExactJsonContractWithoutExtraFields() {
        JsonMapper json = JsonMapper.builder().build();

        assertThat(json.readTree(codec.encode(state()))).isEqualTo(json.readTree(JSON));
    }

    @Test
    void readsIndependentJsonFixture() {
        assertThat(codec.decode(JSON)).isEqualTo(state());
    }

    @Test
    void roundTripPreservesNanosecondsAndMaximumLongSequence() {
        assertThat(codec.decode(codec.encode(state()))).isEqualTo(state());
    }

    @ParameterizedTest
    @ValueSource(strings = {"vehicleId", "lastSequenceNumber", "lastSeenAt", "speedKmh",
        "engineTemperatureC", "batteryVoltage", "odometerKm", "latitude", "longitude"})
    void rejectsMissingFields(String field) {
        var json = (ObjectNode) JsonMapper.builder().build().readTree(JSON);
        json.remove(field);

        assertThatThrownBy(() -> codec.decode(json.toString()))
            .isInstanceOf(LatestStateProjectionException.class).hasCauseInstanceOf(
                JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vehicleId", "lastSequenceNumber", "lastSeenAt", "speedKmh",
        "engineTemperatureC", "batteryVoltage", "odometerKm", "latitude", "longitude"})
    void rejectsNullFields(String field) {
        var json = (ObjectNode) JsonMapper.builder().build().readTree(JSON);
        json.putNull(field);

        assertThatThrownBy(() -> codec.decode(json.toString()))
            .isInstanceOf(LatestStateProjectionException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null"})
    void rejectsInvalidDocuments(String json) {
        assertThatThrownBy(() -> codec.decode(json))
            .isInstanceOf(LatestStateProjectionException.class);
    }

    @Test
    void rejectsTrailingJsonValue() {
        assertThatThrownBy(() -> codec.decode(JSON + " {}"))
            .isInstanceOf(LatestStateProjectionException.class);
    }

    @Test
    void preservesCauseForInvalidTimestamp() {
        assertThatThrownBy(() -> codec.decode(JSON.replace(
            "2026-08-01T10:15:30.123456789Z", "not-an-instant")))
            .isInstanceOf(LatestStateProjectionException.class)
            .hasCauseInstanceOf(JacksonException.class);
    }

    private static LatestVehicleState state() {
        return new LatestVehicleState(
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), Long.MAX_VALUE,
            Instant.parse("2026-08-01T10:15:30.123456789Z"),
            72.4, 91.8, 12.6, 85312, 41.9028, 12.4964);
    }
}
