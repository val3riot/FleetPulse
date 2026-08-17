package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.config.FleetApiProperties;
import it.fleetpulse.simulator.config.GatewayProperties;
import it.fleetpulse.simulator.config.ReconnectProperties;
import it.fleetpulse.simulator.config.VehicleProperties;
import it.fleetpulse.simulator.config.VehicleSimulatorProperties;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSimulatorLifecycleTest {

    @Test
    void disabledSimulatorDoesNotProvisionOrStartWorkloads() {
        AtomicInteger provisioningCalls = new AtomicInteger();
        VehicleSimulatorLifecycle lifecycle =
            new VehicleSimulatorLifecycle(properties(false), () -> {
                provisioningCalls.incrementAndGet();
                return List.of();
            }, ignored -> task(() -> {
            }, () -> {
            }));

        lifecycle.start();

        assertFalse(lifecycle.isRunning());
        assertEquals(0, provisioningCalls.get());
    }

    @Test
    void runsOneWorkloadPerVehicleOnVirtualThreads() throws Exception {
        ProvisionedVehicle first = vehicle(1);
        ProvisionedVehicle second = vehicle(2);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        List<ProvisionedVehicle> createdWorkloads = new ArrayList<>();
        VehicleSimulatorLifecycle lifecycle =
            new VehicleSimulatorLifecycle(properties(true), () -> List.of(first, second),
                vehicle -> {
                    createdWorkloads.add(vehicle);
                    return task(() -> {
                        allVirtual.compareAndSet(true, Thread.currentThread().isVirtual());
                        started.countDown();
                        try {
                            release.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }, () -> {
                    });
                });

        try {
            lifecycle.start();

            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(lifecycle.isRunning());
            assertTrue(allVirtual.get());
            assertEquals(List.of(first, second), createdWorkloads);
        } finally {
            release.countDown();
            lifecycle.stop();
        }

        assertFalse(lifecycle.isRunning());
    }

    @Test
    void stopInterruptsSubmittedWorkloads() throws Exception {
        ProvisionedVehicle vehicle = vehicle(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        VehicleSimulatorLifecycle lifecycle =
            new VehicleSimulatorLifecycle(properties(true), () -> List.of(vehicle),
                ignored -> task(() -> {
                    started.countDown();
                    try {
                        Thread.sleep(Duration.ofMinutes(1));
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                }, () -> closed.set(true)), () -> executor);

        lifecycle.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        lifecycle.stop();

        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertTrue(closed.get());
        assertTrue(executor.isShutdown());
        assertTrue(executor.isTerminated());
        assertFalse(lifecycle.isRunning());
    }

    private static ProvisionedVehicle vehicle(int index) {
        return new ProvisionedVehicle(
            UUID.nameUUIDFromBytes(("vehicle-" + index).getBytes(StandardCharsets.UTF_8)),
            "FP-SIM-%03d".formatted(index));
    }

    private static VehicleSimulatorProperties properties(boolean enabled) {
        return new VehicleSimulatorProperties(enabled, 2,
            new FleetApiProperties(URI.create("http://fleet-api:8080")),
            new GatewayProperties("telemetry-gateway", 7000, Duration.ofSeconds(3)),
            Duration.ofSeconds(1), Duration.ofSeconds(2),
            new ReconnectProperties(Duration.ofMillis(250), Duration.ofSeconds(5), 10, 0.2),
            new VehicleProperties(15_000, 10_000));
    }

    private static VehicleTask task(Runnable run, Runnable close) {
        return new VehicleTask() {
            @Override
            public void run() {
                run.run();
            }

            @Override
            public void close() {
                close.run();
            }
        };
    }
}
