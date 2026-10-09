package it.fleetpulse.processor.health;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import it.fleetpulse.processor.telemetry.kafka.RawTelemetryEventListener;
import it.fleetpulse.processor.telemetry.persistence.PostgreSqlIntegrationSupport;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"fleetpulse.kafka.topics.raw=health.raw",
    "fleetpulse.kafka.topics.rejected=health.rejected", "fleetpulse.kafka.topics.dead-letter=health.dlt",
    "fleetpulse.kafka.consumer.group-id=health-processor"})
@AutoConfigureMockMvc
@DirtiesContext
@ActiveProfiles("test")
class ProcessorHealthIntegrationTest extends PostgreSqlIntegrationSupport {
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");
    @DynamicPropertySource static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
    @BeforeAll static void topics() throws Exception {
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic("health.raw", 1, (short) 1),
                new NewTopic("health.rejected", 1, (short) 1), new NewTopic("health.dlt", 1, (short) 1)))
                .all().get(10, TimeUnit.SECONDS);
        }
    }
    @Autowired MockMvc mvc;
    @Autowired KafkaListenerEndpointRegistry registry;

    @Test void detectsStoppedConsumerOverHttpAndPreservesConfiguredGroupId() throws Exception {
        var container = registry.getListenerContainer(RawTelemetryEventListener.LISTENER_ID);
        assertThat(container.getGroupId()).isEqualTo("health-processor");
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        container.stop();
        try {
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components").doesNotExist());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
            mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
        } finally { container.start(); }
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }
}
