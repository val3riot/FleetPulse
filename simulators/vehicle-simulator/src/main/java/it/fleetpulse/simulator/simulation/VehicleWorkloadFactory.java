package it.fleetpulse.simulator.simulation;

import it.fleetpulse.simulator.config.GatewayProperties;
import it.fleetpulse.simulator.config.VehicleSimulatorProperties;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import it.fleetpulse.simulator.simulation.reconnect.ReconnectingVehicleConnection;
import it.fleetpulse.simulator.tcp.TelemetryFrameEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.net.SocketFactory;
import java.time.Clock;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.random.RandomGenerator;

@Component
public final class VehicleWorkloadFactory implements VehicleWorkloadProvider {

    private static final double INITIAL_LATITUDE = 41.9028;
    private static final double INITIAL_LONGITUDE = 12.4964;

    private final VehicleSimulatorProperties properties;
    private final TelemetryFrameEncoder frameEncoder;
    private final SimulatedVehicleStateFactory stateFactory;
    private final SocketFactory socketFactory;
    private final Clock clock;

    @Autowired
    public VehicleWorkloadFactory(
            VehicleSimulatorProperties properties,
            TelemetryFrameEncoder frameEncoder
    ) {
        this(
                properties,
                frameEncoder,
                new SimulatedVehicleStateFactory(
                        properties.vehicle(),
                        INITIAL_LATITUDE,
                        INITIAL_LONGITUDE
                ),
                SocketFactory.getDefault(),
                Clock.systemUTC()
        );
    }

    VehicleWorkloadFactory(
            VehicleSimulatorProperties properties,
            TelemetryFrameEncoder frameEncoder,
            SimulatedVehicleStateFactory stateFactory,
            SocketFactory socketFactory,
            Clock clock
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.frameEncoder = Objects.requireNonNull(frameEncoder, "frameEncoder");
        this.stateFactory = Objects.requireNonNull(stateFactory, "stateFactory");
        this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VehicleWorkload create(ProvisionedVehicle vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        GatewayProperties gateway = properties.gateway();
        VehicleConnection tcpConnection = new VehicleTcpClient(
                gateway.host(),
                gateway.port(),
                frameEncoder,
                socketFactory,
                gateway.connectTimeout()
        );
        VehicleConnection reconnectingConnection = new ReconnectingVehicleConnection(
                vehicle.externalCode(),
                tcpConnection,
                properties.reconnect()
        );
        RandomGenerator random = new SplittableRandom(
                vehicle.vehicleId().getMostSignificantBits()
                        ^ vehicle.vehicleId().getLeastSignificantBits()
        );
        TelemetryProfile profile = new NormalTelemetryProfile(
                properties.sendInterval(),
                clock,
                random,
                UUID::randomUUID
        );
        return new VehicleWorkload(
                stateFactory.create(vehicle),
                reconnectingConnection,
                profile,
                properties.sendInterval()
        );
    }
}
