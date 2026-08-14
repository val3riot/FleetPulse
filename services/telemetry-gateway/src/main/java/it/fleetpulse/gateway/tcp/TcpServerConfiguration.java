package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TcpServerProperties.class)
public final class TcpServerConfiguration {

    @Bean(destroyMethod = "")
    @ConditionalOnProperty(prefix = "gateway.tcp", name = "enabled", havingValue = "true")
    TcpServer tcpServer(FrameHandler frameHandler, TcpServerProperties properties, FrameDecoder frameDecoder, TelemetryAckEncoder acknowledgementEncoder, MeterRegistry meterRegistry) {
        return new TcpServer(frameHandler, properties, frameDecoder, acknowledgementEncoder, meterRegistry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway.tcp", name = "enabled", havingValue = "true")
    TcpServerLifecycle tcpServerLifecycle(TcpServer tcpServer) {
        return new TcpServerLifecycle(tcpServer);
    }

    @Bean
    FrameDecoder frameDecoder(JsonMapper jsonMapper) {
        return new FrameDecoder(jsonMapper);
    }

    @Bean
    TelemetryAckEncoder telemetryAckEncoder(JsonMapper jsonMapper) {
        return new TelemetryAckEncoder(jsonMapper);
    }
}
