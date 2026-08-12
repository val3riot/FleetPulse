package it.fleetpulse.simulator.fleet.http;

import it.fleetpulse.simulator.fleet.client.CreateFleetVehicleCommand;
import it.fleetpulse.simulator.fleet.client.FleetApiProtocolException;
import it.fleetpulse.simulator.fleet.client.FleetApiUnavailableException;
import it.fleetpulse.simulator.fleet.client.VehicleAlreadyExistsException;
import it.fleetpulse.simulator.fleet.model.FleetVehicle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestFleetApiClientTest {

    private MockRestServiceServer server;
    private RestFleetApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://fleet.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestFleetApiClient(builder.build());
    }

    @Test
    void findsExactExternalCodeAcrossPages() {
        server.expect(once(), requestTo(
                        "http://fleet.test/api/v1/vehicles?query=FP-SIM-002&page=0&size=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(page("FP-SIM-001", 0, 2, false), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "http://fleet.test/api/v1/vehicles?query=FP-SIM-002&page=1&size=100"))
                .andRespond(withSuccess(page("FP-SIM-002", 1, 2, true), MediaType.APPLICATION_JSON));

        Optional<FleetVehicle> result = client.findByExternalCode("FP-SIM-002");

        assertTrue(result.isPresent());
        assertEquals("FP-SIM-002", result.orElseThrow().externalCode());
        server.verify();
    }

    @Test
    void returnsEmptyWhenNoExactMatchExists() {
        server.expect(requestTo("http://fleet.test/api/v1/vehicles?query=FP-SIM-002&page=0&size=100"))
                .andRespond(withSuccess(page("FP-SIM-020", 0, 1, true), MediaType.APPLICATION_JSON));

        assertEquals(Optional.empty(), client.findByExternalCode("FP-SIM-002"));
    }

    @Test
    void createsVehicle() {
        server.expect(requestTo("http://fleet.test/api/v1/vehicles"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(vehicle("FP-SIM-001"), MediaType.APPLICATION_JSON));

        FleetVehicle result = client.createVehicle(
                new CreateFleetVehicleCommand("FP-SIM-001", "SIM001", 15_000, 25_000));

        assertEquals("FP-SIM-001", result.externalCode());
    }

    @Test
    void mapsConflictToDomainException() {
        server.expect(requestTo("http://fleet.test/api/v1/vehicles"))
                .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThrows(VehicleAlreadyExistsException.class, () -> client.createVehicle(
                new CreateFleetVehicleCommand("FP-SIM-001", "SIM001", 15_000, 25_000)));
    }

    @Test
    void mapsServerFailureToUnavailableException() {
        server.expect(requestTo("http://fleet.test/api/v1/vehicles?query=FP-SIM-001&page=0&size=100"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThrows(FleetApiUnavailableException.class,
                () -> client.findByExternalCode("FP-SIM-001"));
    }

    @Test
    void rejectsEmptyResponse() {
        server.expect(requestTo("http://fleet.test/api/v1/vehicles?query=FP-SIM-001&page=0&size=100"))
                .andRespond(withNoContent());

        assertThrows(FleetApiProtocolException.class,
                () -> client.findByExternalCode("FP-SIM-001"));
    }

    private static String page(String externalCode, int page, int totalPages, boolean last) {
        return """
                {"content":[%s],"page":%d,"size":1,"totalElements":%d,
                 "totalPages":%d,"first":%s,"last":%s}
                """.formatted(vehicle(externalCode), page, totalPages, totalPages, page == 0, last);
    }

    private static String vehicle(String externalCode) {
        return """
                {"id":"dc0fc799-0913-4e72-bd2d-8ee8ccf52e22",
                 "externalCode":"%s","plate":"SIM001","status":"ACTIVE",
                 "serviceIntervalKm":15000,"nextServiceAtKm":25000,
                 "createdAt":"2026-08-12T10:00:00Z"}
                """.formatted(externalCode);
    }
}
