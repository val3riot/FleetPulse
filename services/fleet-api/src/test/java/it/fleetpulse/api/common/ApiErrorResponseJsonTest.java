package it.fleetpulse.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;
import org.springframework.boot.test.json.JsonContent;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ApiErrorResponseJsonTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-08-03T08:00:00Z");

    @Autowired
    private JacksonTester<ApiErrorResponse> json;

    /**
     * Verifica il contratto JSON completo quando non sono presenti dettagli puntuali.
     */
    @Test
    @DisplayName("Serializza ApiErrorResponse mantenendo details vuoto")
    void serializesEmptyDetails() throws Exception {
        ApiErrorResponse response =
            new ApiErrorResponse(TIMESTAMP, 404, "VEHICLE_NOT_FOUND", "Vehicle not found",
                "/api/v1/vehicles/97e194a8-64b3-4885-b1e6-25fd482f58c0", List.of());

        JsonContent<ApiErrorResponse> result = json.write(response);

        assertThat(result).extractingJsonPathStringValue("$.timestamp")
            .isEqualTo(TIMESTAMP.toString());
        assertThat(result).extractingJsonPathNumberValue("$.status").isEqualTo(404);
        assertThat(result).extractingJsonPathStringValue("$.code").isEqualTo("VEHICLE_NOT_FOUND");
        assertThat(result).extractingJsonPathStringValue("$.message")
            .isEqualTo("Vehicle not found");
        assertThat(result).extractingJsonPathStringValue("$.path")
            .isEqualTo("/api/v1/vehicles/97e194a8-64b3-4885-b1e6-25fd482f58c0");
        assertThat(result).extractingJsonPathArrayValue("$.details").isEmpty();
        assertThat(result).doesNotHaveJsonPath("$.error");
        assertThat(result).doesNotHaveJsonPath("$.trace");
    }

    /**
     * Verifica la struttura tipizzata dei dettagli di validazione.
     */
    @Test
    @DisplayName("Serializza i dettagli field-level senza campi aggiuntivi")
    void serializesValidationDetails() throws Exception {
        ApiErrorResponse response =
            new ApiErrorResponse(TIMESTAMP, 400, "REQUEST_INVALID", "The request is invalid",
                "/api/v1/vehicles",
                List.of(new ValidationErrorDetail("plate", "must not be blank")));

        JsonContent<ApiErrorResponse> result = json.write(response);

        assertThat(result).extractingJsonPathStringValue("$.details[0].field").isEqualTo("plate");
        assertThat(result).extractingJsonPathStringValue("$.details[0].message")
            .isEqualTo("must not be blank");
        assertThat(result).extractingJsonPathMapValue("$.details[0]")
            .containsOnlyKeys("field", "message");
    }
}
