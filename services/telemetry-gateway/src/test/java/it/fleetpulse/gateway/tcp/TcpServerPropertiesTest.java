package it.fleetpulse.gateway.tcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TcpServerPropertiesTest {

    @Test
    void acceptsValidPortRangeIncludingEphemeralPort() {
        assertDoesNotThrow(() -> new TcpServerProperties(false, 0));
        assertDoesNotThrow(() -> new TcpServerProperties(true, 65_535));
    }

    @Test
    void rejectsPortsOutsideTcpRange() {
        assertThrows(IllegalArgumentException.class, () -> new TcpServerProperties(true, -1));
        assertThrows(IllegalArgumentException.class, () -> new TcpServerProperties(true, 65_536));
    }
}
