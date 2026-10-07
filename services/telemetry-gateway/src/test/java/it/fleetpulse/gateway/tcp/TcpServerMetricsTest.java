package it.fleetpulse.gateway.tcp;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.fleetpulse.gateway.tcp.exception.InvalidTelemetryException;
import it.fleetpulse.gateway.tcp.exception.MalformedTelemetryException;
import it.fleetpulse.gateway.tcp.exception.UnsupportedProtocolVersionException;
import it.fleetpulse.protocol.frame.FrameStreamClosedException;
import it.fleetpulse.protocol.frame.FrameTooLargeException;
import it.fleetpulse.protocol.frame.InvalidFrameLengthException;
import it.fleetpulse.protocol.frame.TruncatedFrameHeaderException;
import it.fleetpulse.protocol.frame.TruncatedFramePayloadException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TcpServerMetricsTest {
    @Test
    void classifiesProtocolFailuresWithFiniteReasons() {
        var registry = new SimpleMeterRegistry();
        var metrics = new TcpServerMetrics(registry, new HashSet<Socket>());
        Map<String, IOException> cases = Map.of(
            "too_large", new FrameTooLargeException(100_000),
            "invalid_length", new InvalidFrameLengthException(0),
            "truncated", new TruncatedFrameHeaderException(1),
            "malformed", new MalformedTelemetryException("private-payload"),
            "invalid", new InvalidTelemetryException("private-payload"),
            "unsupported_version", new UnsupportedProtocolVersionException(999));
        cases.forEach((reason, failure) -> {
            metrics.recordFrameRejectionIfApplicable(failure);
            assertThat(registry.get("fleetpulse.gateway.frames.rejected")
                .tag("reason", reason).counter().count()).isEqualTo(1);
        });
        metrics.recordFrameRejectionIfApplicable(new TruncatedFramePayloadException(10, 2));
        metrics.recordFrameRejectionIfApplicable(new FrameStreamClosedException());
        metrics.recordFrameRejectionIfApplicable(new SocketTimeoutException());
        metrics.recordFrameRejectionIfApplicable(new IOException("transport failure"));
        assertThat(registry.get("fleetpulse.gateway.frames.rejected")
            .tag("reason", "truncated").counter().count()).isEqualTo(2);
        assertThat(registry.find("fleetpulse.gateway.frames.rejected").counters()).hasSize(6);
        assertThat(registry.getMeters()).allSatisfy(meter ->
            assertThat(meter.getId().getTags().toString()).doesNotContain("private-payload"));
    }
}
