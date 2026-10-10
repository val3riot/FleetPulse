package it.fleetpulse.gateway.telemetry.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KafkaSendBudgetTest {
    @Test
    void acceptsCoordinatedProducerBudgetsIncludingStringEnvironmentValues() {
        assertDoesNotThrow(() -> KafkaSendBudget.validate(Map.of("max.block.ms", "1000",
                "request.timeout.ms", "1000", "delivery.timeout.ms", "4000", "linger.ms", "0"),
                Duration.ofSeconds(5)));
    }

    @ParameterizedTest
    @CsvSource({"60000,1000,4000,0", "1000,1000,5000,0", "1000,4000,4000,1",
            "0,1000,4000,0", "1000,0,4000,0", "1000,1000,0,0", "1000,1000,4000,-1"})
    void rejectsInconsistentTimeouts(long block, long request, long delivery, long linger) {
        assertThrows(IllegalArgumentException.class, () -> KafkaSendBudget.validate(Map.of(
                "max.block.ms", block, "request.timeout.ms", request,
                "delivery.timeout.ms", delivery, "linger.ms", linger), Duration.ofSeconds(5)));
    }

    @Test
    void rejectsImplicitProducerDefaults() {
        Map<String, Object> config = new HashMap<>(Map.of("max.block.ms", 1000,
                "request.timeout.ms", 1000, "delivery.timeout.ms", 4000, "linger.ms", 0));
        config.remove("max.block.ms");
        assertThrows(IllegalArgumentException.class,
                () -> KafkaSendBudget.validate(config, Duration.ofSeconds(5)));
    }
}
