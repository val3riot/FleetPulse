package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpServerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TcpServerConfiguration.class, JsonConfiguration.class);

    @Test
    void keepsTcpListenerDisabledWithoutProductionFrameHandler() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertNotNull(context.getBean(FrameDecoder.class));
            assertFalse(context.containsBean("tcpServer"));
            assertFalse(context.containsBean("tcpServerLifecycle"));
            TcpServerProperties properties = context.getBean(TcpServerProperties.class);
            assertFalse(properties.enabled());
            assertEquals(7000, properties.port());
            assertEquals(100, properties.maxConnections());
        });
    }

    @Test
    void startsTcpListenerWhenExplicitlyEnabledWithTestOnlyHandler() {
        contextRunner
                .withUserConfiguration(TestHandlerConfiguration.class)
                .withPropertyValues("gateway.tcp.enabled=true", "gateway.tcp.port=0")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertNotNull(context.getBean(TcpServer.class));
                    assertTrue(context.getBean(TcpServerLifecycle.class).isRunning());
                });
    }

    @Test
    void bindsDefaultTcpTimeoutProperties() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());

            TcpServerProperties properties =
                    context.getBean(TcpServerProperties.class);

            assertEquals(
                    Duration.ofSeconds(30),
                    properties.readTimeout()
            );

            assertEquals(
                    Duration.ofSeconds(5),
                    properties.shutdownGracePeriod()
            );
        });
    }

    @Test
    void bindsConfiguredTcpTimeoutProperties() {
        contextRunner
                .withPropertyValues(
                        "gateway.tcp.read-timeout=750ms",
                        "gateway.tcp.shutdown-grace-period=2s"
                )
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    TcpServerProperties properties = context.getBean(TcpServerProperties.class);
                    assertEquals(Duration.ofMillis(750), properties.readTimeout());
                    assertEquals(Duration.ofSeconds(2), properties.shutdownGracePeriod());
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class JsonConfiguration {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TestHandlerConfiguration {

        @Bean
        FrameHandler frameHandler() {
            return message -> { };
        }
    }
}
