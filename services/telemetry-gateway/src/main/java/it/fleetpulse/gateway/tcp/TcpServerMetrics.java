package it.fleetpulse.gateway.tcp;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.net.Socket;
import java.util.Objects;
import java.util.Set;

final class TcpServerMetrics {

    private final Counter acceptedConnections;
    private final Counter rejectedConnections;
    private final Counter capacityRejectedConnections;
    private final Counter receivedFrames;
    private final Counter connectionFailures;

    TcpServerMetrics(MeterRegistry registry, Set<Socket> clients) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(clients, "clients must not be null");

        acceptedConnections = Counter.builder("fleetpulse.gateway.connections.accepted")
                .description("Total TCP connections accepted by the gateway")
                .register(registry);
        rejectedConnections = Counter.builder("fleetpulse.gateway.connections.rejected")
                .description("Total TCP connections rejected during dispatch")
                .register(registry);
        capacityRejectedConnections = Counter.builder("fleetpulse.gateway.tcp.connections.capacity.rejected")
                .description("Total TCP connections rejected because maxConnections was reached")
                .register(registry);
        receivedFrames = Counter.builder("fleetpulse.gateway.frames.received")
                .description("Total telemetry frames received by the gateway")
                .register(registry);
        connectionFailures = Counter.builder("fleetpulse.gateway.connections.failures")
                .description("Total TCP connection failures")
                .register(registry);
        Gauge.builder("fleetpulse.gateway.connections.active", clients, Set::size)
                .description("Current active TCP connections")
                .register(registry);
    }

    void connectionAccepted() {
        acceptedConnections.increment();
    }

    void connectionRejected() {
        rejectedConnections.increment();
    }

    void connectionCapacityRejected() {
        capacityRejectedConnections.increment();
    }

    void frameReceived() {
        receivedFrames.increment();
    }

    void connectionFailed() {
        connectionFailures.increment();
    }

    long acceptedConnections() {
        return (long) acceptedConnections.count();
    }

    long rejectedConnections() {
        return (long) rejectedConnections.count();
    }

    long capacityRejectedConnections() {
        return (long) capacityRejectedConnections.count();
    }

    long receivedFrames() { return (long) receivedFrames.count(); }

    long connectionFailures() {
        return (long) connectionFailures.count();
    }
}
