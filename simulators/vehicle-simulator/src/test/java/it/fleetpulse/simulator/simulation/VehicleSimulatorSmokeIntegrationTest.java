package it.fleetpulse.simulator.simulation;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.fleetpulse.protocol.TelemetryMessage;
import it.fleetpulse.protocol.frame.LengthPrefixedFrameCodec;
import it.fleetpulse.simulator.VehicleSimulatorApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSimulatorSmokeIntegrationTest {

    private static final UUID VEHICLE_ID = UUID.fromString("97e194a8-64b3-4885-b1e6-25fd482f58c0");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void provisionsVehicleStartsWorkloadAndSendsARealTcpFrame() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        HttpServer fleetApi = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        fleetApi.createContext("/api/v1/vehicles", VehicleSimulatorSmokeIntegrationTest::handleFleetApi);
        fleetApi.start();

        try (ServerSocket gateway = new ServerSocket(0, 1, loopback)) {
            CompletableFuture<TelemetryMessage> received = CompletableFuture.supplyAsync(() -> {
                try (Socket client = gateway.accept()) {
                    byte[] payload = LengthPrefixedFrameCodec.read(client.getInputStream());
                    return OBJECT_MAPPER.readValue(payload, TelemetryMessage.class);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            });

            ConfigurableApplicationContext context = new SpringApplicationBuilder(
                    VehicleSimulatorApplication.class
            ).logStartupInfo(false).run(
                    "--simulator.enabled=true",
                    "--simulator.vehicle-count=1",
                    "--simulator.fleet-api.base-url=http://" + loopback.getHostAddress()
                            + ":" + fleetApi.getAddress().getPort(),
                    "--simulator.gateway.host=" + loopback.getHostAddress(),
                    "--simulator.gateway.port=" + gateway.getLocalPort(),
                    "--simulator.send-interval=50ms",
                    "--simulator.shutdown-grace-period=2s",
                    "--simulator.reconnect.initial-backoff=10ms",
                    "--simulator.reconnect.max-backoff=50ms",
                    "--simulator.reconnect.max-attempts=3",
                    "--simulator.reconnect.jitter-ratio=0",
                    "--simulator.vehicle.service-interval-km=15000",
                    "--simulator.vehicle.initial-odometer-km=10000",
                    "--spring.main.banner-mode=off",
                    "--logging.level.root=WARN"
            );

            VehicleSimulatorLifecycle lifecycle = context.getBean(VehicleSimulatorLifecycle.class);
            try {
                TelemetryMessage message = received.get(5, TimeUnit.SECONDS);

                assertTrue(lifecycle.isRunning());
                assertEquals(VEHICLE_ID, message.vehicleId());
                assertEquals(0, message.sequenceNumber());
            } finally {
                context.close();
            }

            assertFalse(lifecycle.isRunning());
        } finally {
            fleetApi.stop(0);
        }
    }

    private static void handleFleetApi(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String response;
        int status;
        if ("GET".equals(exchange.getRequestMethod())) {
            response = """
                    {"content":[],"page":0,"size":100,"totalElements":0,"totalPages":0,
                    "first":true,"last":true}
                    """;
            status = 200;
        } else if ("POST".equals(exchange.getRequestMethod())) {
            response = """
                    {"id":"97e194a8-64b3-4885-b1e6-25fd482f58c0",
                    "externalCode":"FP-SIM-001","plate":"SIM001","status":"ACTIVE",
                    "serviceIntervalKm":15000,"nextServiceAtKm":25000,
                    "createdAt":"2026-08-12T12:00:00Z"}
                    """;
            status = 201;
        } else {
            response = "";
            status = 405;
        }
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
