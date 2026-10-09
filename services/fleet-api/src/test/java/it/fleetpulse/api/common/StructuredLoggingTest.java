package it.fleetpulse.api.common;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import tools.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLoggingTest {
    @Test
    void serializesEcsWithTypedFieldsAndCorrelation() {
        var context = new LoggerContext();
        context.putObject(Environment.class.getName(),
                new MockEnvironment().withProperty("spring.application.name", "fleet-api"));
        var encoder = new StructuredLogEncoder();
        encoder.setContext(context);
        encoder.setFormat("ecs");
        encoder.start();
        try {
            var event = new LoggingEvent(getClass().getName(), context.getLogger("test"),
                    Level.INFO, "HTTP request completed", null, null);
            event.setMDCPropertyMap(Map.of("requestId", "request-123"));
            event.addKeyValuePair(new KeyValuePair("event.action", "http.request.completed"));
            event.addKeyValuePair(new KeyValuePair("status", 200));
            String output = new String(encoder.encode(event), StandardCharsets.UTF_8);
            var json = new ObjectMapper().readTree(output);
            assertThat(json.path("@timestamp").asString()).isNotBlank();
            assertThat(json.path("log").path("level").asString()).isEqualTo("INFO");
            assertThat(json.path("service").path("name").asString()).isEqualTo("fleet-api");
            assertThat(json.path("ecs").path("version").asString()).isNotBlank();
            assertThat(json.path("requestId").asString()).isEqualTo("request-123");
            assertThat(json.path("event").path("action").asString())
                    .isEqualTo("http.request.completed");
            assertThat(json.path("status").isNumber()).isTrue();
            assertThat(output).doesNotContain("error.stack_trace");
            assertThat(output).endsWith("\n");
        } finally {
            encoder.stop();
            context.stop();
        }
    }
}
