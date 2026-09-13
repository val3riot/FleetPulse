package it.fleetpulse.api.telemetry;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryDtoValidationTest {
    private static final ValidatorFactory VALIDATOR_FACTORY =
        Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @AfterAll
    static void closeValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void acceptsAResponseMatchingTheDocumentedSchema() {
        TelemetryHistoryResponse response =
            new TelemetryHistoryResponse(List.of(validSample()), 0, 50, 1, 1, true, true);

        assertThat(VALIDATOR.validate(response)).isEmpty();
    }

    @Test
    void rejectsInvalidSampleValuesThroughCascadedValidation() {
        TelemetrySampleResponse invalidSample = new TelemetrySampleResponse(0L, null, null, -1,
            null, null, null, -1, 91.8, -1, -1, -90.1, 180.1);
        TelemetryHistoryResponse response =
            new TelemetryHistoryResponse(List.of(invalidSample), -1, -1, -1, -1, true, false);

        assertThat(VALIDATOR.validate(response)).extracting(violation -> violation.getPropertyPath()
                .toString())
            .contains("page", "size", "totalElements", "totalPages", "content[0].id",
                "content[0].messageId", "content[0].vehicleId", "content[0].sequenceNumber",
                "content[0].observedAt", "content[0].receivedAt", "content[0].processedAt",
                "content[0].speedKmh", "content[0].batteryVoltage", "content[0].odometerKm",
                "content[0].latitude", "content[0].longitude");
    }

    private TelemetrySampleResponse validSample() {
        Instant observedAt = Instant.parse("2026-08-01T10:15:30Z");
        return new TelemetrySampleResponse(1254L,
            UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22"),
            UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0"), 42, observedAt,
            observedAt.plusMillis(83), observedAt.plusMillis(150), 72.4, 91.8, 12.6, 85312,
            41.9028, 12.4964);
    }
}
