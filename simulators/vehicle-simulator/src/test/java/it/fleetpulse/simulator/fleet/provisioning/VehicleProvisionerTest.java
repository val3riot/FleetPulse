package it.fleetpulse.simulator.fleet.provisioning;

import it.fleetpulse.simulator.config.FleetApiProperties;
import it.fleetpulse.simulator.config.GatewayProperties;
import it.fleetpulse.simulator.config.ReconnectProperties;
import it.fleetpulse.simulator.config.VehicleProperties;
import it.fleetpulse.simulator.config.VehicleSimulatorProperties;
import it.fleetpulse.simulator.fleet.client.CreateFleetVehicleCommand;
import it.fleetpulse.simulator.fleet.client.FleetApiClient;
import it.fleetpulse.simulator.fleet.client.VehicleAlreadyExistsException;
import it.fleetpulse.simulator.fleet.model.FleetVehicle;
import it.fleetpulse.simulator.fleet.model.ProvisionedVehicle;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VehicleProvisionerTest {

    @Test
    void reusesExistingVehiclesAndCreatesOnlyMissingOnes() {
        FakeFleetApiClient client = new FakeFleetApiClient();
        FleetVehicle first = vehicle("FP-SIM-001", "SIM001");
        FleetVehicle second = vehicle("FP-SIM-002", "SIM002");
        FleetVehicle third = vehicle("FP-SIM-003", "SIM003");
        client.searchReturns("FP-SIM-001", Optional.of(first));
        client.searchReturns("FP-SIM-002", Optional.empty());
        client.searchReturns("FP-SIM-003", Optional.of(third));
        client.createdVehicle = second;

        List<ProvisionedVehicle> result = new VehicleProvisioner(client, properties(3)).provision();

        assertEquals(List.of(new ProvisionedVehicle(first.id(), first.externalCode()),
            new ProvisionedVehicle(second.id(), second.externalCode()),
            new ProvisionedVehicle(third.id(), third.externalCode())), result);
        assertEquals(List.of("FP-SIM-001", "FP-SIM-002", "FP-SIM-003"), client.searches);
        assertEquals(List.of(new CreateFleetVehicleCommand("FP-SIM-002", "SIM002", 15_000, 25_000)),
            client.creates);
    }

    @Test
    void resolvesConcurrentCreateConflictWithSecondLookup() {
        FakeFleetApiClient client = new FakeFleetApiClient();
        FleetVehicle concurrent = vehicle("FP-SIM-001", "SIM001");
        client.searchReturns("FP-SIM-001", Optional.empty(), Optional.of(concurrent));
        client.conflictOnCreate = true;

        List<ProvisionedVehicle> result = new VehicleProvisioner(client, properties(1)).provision();

        assertEquals(List.of(new ProvisionedVehicle(concurrent.id(), concurrent.externalCode())),
            result);
        assertEquals(List.of("FP-SIM-001", "FP-SIM-001"), client.searches);
    }

    @Test
    void failsWhenConflictingVehicleCannotBeFound() {
        FakeFleetApiClient client = new FakeFleetApiClient();
        client.searchReturns("FP-SIM-001", Optional.empty(), Optional.empty());
        client.conflictOnCreate = true;

        assertThrows(VehicleProvisioningException.class,
            () -> new VehicleProvisioner(client, properties(1)).provision());
    }

    private static FleetVehicle vehicle(String externalCode, String plate) {
        return new FleetVehicle(UUID.randomUUID(), externalCode, plate);
    }

    private static VehicleSimulatorProperties properties(int count) {
        return new VehicleSimulatorProperties(true, count,
            new FleetApiProperties(URI.create("http://localhost:8080")),
            new GatewayProperties("localhost", 7000, Duration.ofSeconds(3)), Duration.ofSeconds(1),
            Duration.ofSeconds(5),
            new ReconnectProperties(Duration.ofMillis(250), Duration.ofSeconds(5), 10, 0.2),
            new VehicleProperties(15_000, 10_000));
    }

    private static final class FakeFleetApiClient implements FleetApiClient {

        private final Map<String, ArrayDeque<Optional<FleetVehicle>>> searchResults =
            new HashMap<>();
        private final List<String> searches = new ArrayList<>();
        private final List<CreateFleetVehicleCommand> creates = new ArrayList<>();
        private FleetVehicle createdVehicle;
        private boolean conflictOnCreate;

        @SafeVarargs
        final void searchReturns(String externalCode, Optional<FleetVehicle>... results) {
            searchResults.put(externalCode, new ArrayDeque<>(List.of(results)));
        }

        @Override
        public Optional<FleetVehicle> findByExternalCode(String externalCode) {
            searches.add(externalCode);
            ArrayDeque<Optional<FleetVehicle>> results = searchResults.get(externalCode);
            if (results == null || results.isEmpty()) {
                return Optional.empty();
            }
            return results.size() == 1 ? results.peekFirst() : results.removeFirst();
        }

        @Override
        public FleetVehicle createVehicle(CreateFleetVehicleCommand command) {
            creates.add(command);
            if (conflictOnCreate) {
                throw new VehicleAlreadyExistsException(command.externalCode(), null);
            }
            return createdVehicle;
        }
    }
}
