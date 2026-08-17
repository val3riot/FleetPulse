package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.config.VehicleSimulatorProperties;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import it.fleetpulse.simulator.fleet.provisioning.FleetProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public final class VehicleSimulatorLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(VehicleSimulatorLifecycle.class);

    private final VehicleSimulatorProperties properties;
    private final FleetProvisioner provisioner;
    private final VehicleWorkloadProvider workloadFactory;
    private final Supplier<ExecutorService> executorFactory;

    private volatile boolean running;
    private ExecutorService executor;
    private List<VehicleTask> tasks = List.of();

    @Autowired
    public VehicleSimulatorLifecycle(VehicleSimulatorProperties properties,
        FleetProvisioner provisioner, VehicleWorkloadProvider workloadFactory) {
        this(properties, provisioner, workloadFactory, Executors::newVirtualThreadPerTaskExecutor);
    }

    VehicleSimulatorLifecycle(VehicleSimulatorProperties properties, FleetProvisioner provisioner,
        VehicleWorkloadProvider workloadFactory, Supplier<ExecutorService> executorFactory) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.workloadFactory = Objects.requireNonNull(workloadFactory, "workloadFactory");
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!properties.enabled()) {
            log.info("Vehicle simulator is disabled");
            return;
        }

        log.info("Provisioning {} simulated vehicles", properties.vehicleCount());
        List<ProvisionedVehicle> vehicles = provisioner.provision();
        ExecutorService newExecutor = executorFactory.get();
        try {
            List<VehicleTask> newTasks = vehicles.stream().map(workloadFactory::create).toList();
            tasks = newTasks;
            for (VehicleTask task : newTasks) {
                newExecutor.submit(task);
            }
            executor = newExecutor;
            running = true;
            log.info("Started {} vehicle workloads on virtual threads", vehicles.size());
        } catch (RuntimeException startupFailure) {
            tasks.forEach(VehicleTask::close);
            tasks = List.of();
            newExecutor.shutdownNow();
            throw startupFailure;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        List<VehicleTask> tasksToStop = tasks;
        tasks = List.of();
        ExecutorService executorToStop = executor;
        executor = null;
        tasksToStop.forEach(VehicleTask::close);
        if (executorToStop != null) {
            executorToStop.shutdownNow();
            awaitTermination(executorToStop);
        }
        log.info("Vehicle simulator lifecycle stopped");
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void awaitTermination(ExecutorService executorToStop) {
        try {
            if (!executorToStop.awaitTermination(properties.shutdownGracePeriod().toMillis(),
                TimeUnit.MILLISECONDS)) {
                log.warn("Vehicle workload executor did not terminate within {} ms",
                    properties.shutdownGracePeriod().toMillis());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for vehicle workloads to stop");
        }
    }
}
