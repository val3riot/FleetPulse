package it.fleetpulse.gateway.tcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TcpServerPropertiesTest {

    @Test
    void acceptsValidPortRangeIncludingEphemeralPort() {
        assertDoesNotThrow(() -> new TcpServerProperties(false, 0, 1));
        assertDoesNotThrow(() -> new TcpServerProperties(true, 65_535, 100));
    }

    @Test
    void rejectsPortsOutsideTcpRange() {
        assertThrows(IllegalArgumentException.class, () -> new TcpServerProperties(true, -1, 100));
        assertThrows(IllegalArgumentException.class, () -> new TcpServerProperties(true, 65_536, 100));
    }

    @Test
    void rejectsNonPositiveMaximumConnections() {
        assertThrows(IllegalArgumentException.class, () -> new TcpServerProperties(true, 7000, 0));
        assertThrows(IllegalArgumentException.class, () -> new TcpServerProperties(true, 7000, -1));
    }
}
