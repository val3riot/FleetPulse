package it.fleetpulse.gateway.tcp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TcpServerPropertiesTest {

    @Test
    void acceptsValidPortRangeIncludingEphemeralPort() {
        assertDoesNotThrow(() -> new TcpServerProperties(false, 0, 1, Duration.ofSeconds(10),
            Duration.ofSeconds(5)));
        assertDoesNotThrow(() -> new TcpServerProperties(true, 65_535, 100, Duration.ofSeconds(10),
            Duration.ofSeconds(5)));
    }

    @Test
    void rejectsPortsOutsideTcpRange() {
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, -1, 100, Duration.ofSeconds(10),
                Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 65_536, 100, Duration.ofSeconds(10),
                Duration.ofSeconds(5)));
    }

    @Test
    void rejectsNonPositiveMaximumConnections() {
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 7000, 0, Duration.ofSeconds(10),
                Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 7000, -1, Duration.ofSeconds(10),
                Duration.ofSeconds(5)));
    }

    @Test
    void rejectsNonPositiveReadTimeout() {
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 7000, 100, Duration.ofSeconds(0),
                Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 7000, 100, Duration.ofSeconds(-1),
                Duration.ofSeconds(5)));
    }

    @Test
    void rejectsReadTimeoutTooLargeForSocketTimeout() {
        Duration tooLarge = Duration.ofMillis((long) Integer.MAX_VALUE + 1);
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 7000, 100, tooLarge, Duration.ofSeconds(5)));
    }

    @Test
    void acceptsReadTimeoutWithinSocketTimeoutRange() {
        assertDoesNotThrow(() -> new TcpServerProperties(true, 7000, 100, Duration.ofMillis(1),
            Duration.ofSeconds(5)));
        assertDoesNotThrow(
            () -> new TcpServerProperties(true, 7000, 100, Duration.ofMillis(Integer.MAX_VALUE),
                Duration.ofSeconds(5)));
    }

    @Test
    void rejectsNonPositiveShutdownGracePeriod() {
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 7000, 100, Duration.ofSeconds(10),
                Duration.ofSeconds(0)));
        assertThrows(IllegalArgumentException.class,
            () -> new TcpServerProperties(true, 7000, 100, Duration.ofSeconds(10),
                Duration.ofSeconds(-1)));
    }
}
