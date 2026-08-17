package it.fleetpulse.simulator.fleet.http;

import it.fleetpulse.simulator.fleet.client.CreateFleetVehicleCommand;
import it.fleetpulse.simulator.fleet.client.FleetApiClient;
import it.fleetpulse.simulator.fleet.client.FleetApiException;
import it.fleetpulse.simulator.fleet.client.FleetApiProtocolException;
import it.fleetpulse.simulator.fleet.client.FleetApiRequestException;
import it.fleetpulse.simulator.fleet.client.FleetApiUnavailableException;
import it.fleetpulse.simulator.fleet.client.VehicleAlreadyExistsException;
import it.fleetpulse.simulator.fleet.model.FleetVehicle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Component
public final class RestFleetApiClient implements FleetApiClient {


    private final RestClient restClient;

    RestFleetApiClient(RestClient fleetApiRestClient) {
        this.restClient = fleetApiRestClient;
    }

    @Override
    public Optional<FleetVehicle> findByExternalCode(String externalCode) {
        if (externalCode == null || externalCode.isBlank()) {
            throw new IllegalArgumentException("externalCode must not be blank");
        }
        int page = 0;
        while (true) {
            int finalPage = page;
            VehiclePageResponse response;
            try {
                response = restClient.get().uri(uriBuilder -> uriBuilder.path("/api/v1/vehicles")
                    .queryParam("query", externalCode).queryParam("page", finalPage)
                    .queryParam("size", 100).build()).retrieve().body(VehiclePageResponse.class);
            } catch (RestClientException exception) {
                throw translate("search vehicle " + externalCode, exception);
            }

            if (response == null) {
                throw new FleetApiProtocolException(
                    "Fleet API returned an empty vehicle search response");
            }
            List<VehicleResponse> content = response.content();
            if (content == null || response.totalPages() < 0) {
                throw new FleetApiProtocolException("Fleet API returned an invalid vehicle page");
            }

            Optional<FleetVehicle> exactMatch =
                content.stream().filter(vehicle -> externalCode.equals(vehicle.externalCode()))
                    .map(RestFleetApiClient::toFleetVehicle).findFirst();

            if (exactMatch.isPresent()) {
                return exactMatch;
            }

            page++;

            if (page >= response.totalPages()) {
                return Optional.empty();
            }
        }
    }

    @Override
    public FleetVehicle createVehicle(CreateFleetVehicleCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        CreateVehicleRequest request =
            new CreateVehicleRequest(command.externalCode(), command.plate(),
                command.serviceIntervalKm(), command.nextServiceAtKm());
        VehicleResponse response;
        try {
            response =
                restClient.post().uri("/api/v1/vehicles").contentType(MediaType.APPLICATION_JSON)
                    .body(request).retrieve().body(VehicleResponse.class);

        } catch (HttpClientErrorException.Conflict exception) {
            throw new VehicleAlreadyExistsException(command.externalCode(), exception);
        } catch (RestClientException exception) {
            throw translate("create vehicle " + command.externalCode(), exception);
        }
        if (response == null) {
            throw new FleetApiProtocolException(
                "Fleet API returned an empty response while creating vehicle " +
                    command.externalCode());
        }
        return toFleetVehicle(response);
    }


    private static FleetVehicle toFleetVehicle(VehicleResponse response) {
        return new FleetVehicle(response.id(), response.externalCode(), response.plate());
    }

    private static FleetApiException translate(String operation, RestClientException exception) {
        if (exception instanceof ResourceAccessException) {
            return new FleetApiUnavailableException(
                "Fleet API unavailable while attempting to " + operation, exception);
        }
        if (exception instanceof HttpStatusCodeException statusException) {
            int statusCode = statusException.getStatusCode().value();
            if (statusException.getStatusCode().is5xxServerError()) {
                return new FleetApiUnavailableException(
                    "Fleet API failed with status " + statusCode + " while attempting to " +
                        operation, exception);
            }
            return new FleetApiRequestException(statusCode,
                "Fleet API rejected request with status " + statusCode + " while attempting to " +
                    operation, exception);
        }
        return new FleetApiProtocolException(
            "Fleet API response could not be processed while attempting to " + operation,
            exception);
    }
}
