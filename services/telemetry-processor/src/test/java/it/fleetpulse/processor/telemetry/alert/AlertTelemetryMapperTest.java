package it.fleetpulse.processor.telemetry.alert;

import it.fleetpulse.contracts.telemetry.TelemetryData;
import it.fleetpulse.contracts.telemetry.TelemetryEvent;
import it.fleetpulse.contracts.telemetry.TelemetryEventVersions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AlertTelemetryMapperTest {
    private static final UUID MESSAGE_ID =
        UUID.fromString("dc0fc799-0913-4e72-bd2d-8ee8ccf52e22");
    private static final UUID VEHICLE_ID =
        UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private final AlertTelemetryMapper mapper = new AlertTelemetryMapper();

    @Test
    void mapsOnlyDataRequiredByAlertRules() {
        var event = new TelemetryEvent(TelemetryEventVersions.V1, MESSAGE_ID, VEHICLE_ID, 42,
            Instant.parse("2026-08-01T10:15:30Z"),
            Instant.parse("2026-08-01T10:15:30.083Z"),
            new TelemetryData(72.4, 111.0, 11.7, 90_000, 41.9028, 12.4964));

        assertThat(mapper.toSample(event)).isEqualTo(
            new AlertTelemetrySample(MESSAGE_ID, VEHICLE_ID, 111.0, 11.7, 90_000));
    }
}
