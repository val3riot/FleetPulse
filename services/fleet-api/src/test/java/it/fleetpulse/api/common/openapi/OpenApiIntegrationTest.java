package it.fleetpulse.api.common.openapi;

import it.fleetpulse.api.vehicle.PostgreSqlIntegrationSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class OpenApiIntegrationTest extends PostgreSqlIntegrationSupport {

    private static final String OPEN_API_PATH = "/v3/api-docs";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Verifica pubblicazione, versione e metadati generali della specifica.
     */
    @Test
    @DisplayName("Pubblica una specifica OpenAPI 3.1 con i metadati FleetPulse")
    void exposesOpenApiSpecification() throws Exception {
        mockMvc.perform(get(OPEN_API_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").value("3.1.0"))
                .andExpect(jsonPath("$.info.title").value("FleetPulse API"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }

    /**
     * Verifica che siano pubblicati soltanto i quattro endpoint vehicle implementati.
     */
    @Test
    @DisplayName("Documenta tutti e soli gli endpoint vehicle operativi")
    void documentsImplementedVehicleEndpoints() throws Exception {
        mockMvc.perform(get(OPEN_API_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths.length()").value(3))
                .andExpect(jsonPath("$.paths['/api/v1/vehicles'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles/{vehicleId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles/{vehicleId}/status'].patch").exists())
                .andExpect(jsonPath("$.paths['/api/v1/dashboard']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/alerts']").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles/{vehicleId}/telemetry']").doesNotExist());
    }

    /**
     * Verifica request, response 201 e header Location della registrazione.
     */
    @Test
    @DisplayName("Documenta la create con request, response 201 e Location")
    void documentsVehicleCreationContract() throws Exception {
        mockMvc.perform(get(OPEN_API_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].post.requestBody.required"
                ).value(true))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].post.requestBody.content['application/json'].schema.$ref"
                ).value(endsWith("/CreateVehicleRequest")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].post.responses['201'].headers.Location.schema.format"
                ).value("uri"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].post.responses['201'].content['application/json'].schema.$ref"
                ).value(endsWith("/VehicleResponse")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].post.responses['200']"
                ).doesNotExist());
    }

    /**
     * Verifica path, request e response del dettaglio e del cambio stato.
     */
    @Test
    @DisplayName("Documenta dettaglio e cambio stato del veicolo")
    void documentsVehicleDetailAndStatusChangeContracts() throws Exception {
        mockMvc.perform(get(OPEN_API_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}'].get.parameters[?(@.name == 'vehicleId')].required"
                ).value(true))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}'].get.parameters[?(@.name == 'vehicleId')].schema.format"
                ).value("uuid"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}'].get.responses['200'].content['application/json'].schema.$ref"
                ).value(endsWith("/VehicleResponse")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}/status'].patch.requestBody.required"
                ).value(true))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}/status'].patch.requestBody.content['application/json'].schema.$ref"
                ).value(endsWith("/ChangeVehicleStatusRequest")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}/status'].patch.responses['200'].content['application/json'].schema.$ref"
                ).value(endsWith("/VehicleResponse")));
    }

    /**
     * Verifica filtri, limiti e default della lista paginata.
     */
    @Test
    @DisplayName("Documenta filtri e vincoli della paginazione")
    void documentsVehicleListParameters() throws Exception {
        String parameters = "$.paths['/api/v1/vehicles'].get.parameters";

        mockMvc.perform(get(OPEN_API_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath(parameters + "[*].name")
                        .value(hasItems("query", "status", "page", "size", "sort")))
                .andExpect(jsonPath(parameters + "[?(@.name == 'page')].schema.default").value(0))
                .andExpect(jsonPath(parameters + "[?(@.name == 'page')].schema.minimum").value(0))
                .andExpect(jsonPath(parameters + "[?(@.name == 'size')].schema.default").value(20))
                .andExpect(jsonPath(parameters + "[?(@.name == 'size')].schema.minimum").value(1))
                .andExpect(jsonPath(parameters + "[?(@.name == 'size')].schema.maximum").value(100))
                .andExpect(jsonPath(parameters + "[?(@.name == 'sort')].schema.default")
                        .value("createdAt,desc"))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].get.responses['200'].content['application/json'].schema.$ref"
                ).value(endsWith("/PagedResponseVehicleResponse")))
                .andExpect(jsonPath(
                        "$.components.schemas.PagedResponseVehicleResponse.properties.content.items.$ref"
                ).value(endsWith("/VehicleResponse")))
                .andExpect(jsonPath(
                        "$.components.schemas.PagedResponseVehicleResponse.properties.totalElements"
                ).exists());
    }

    /**
     * Verifica gli schemi delle request, delle response e degli enum pubblici.
     */
    @Test
    @DisplayName("Documenta gli schemi vehicle e i valori dello status")
    void documentsVehicleSchemas() throws Exception {
        mockMvc.perform(get(OPEN_API_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CreateVehicleRequest.required")
                        .value(hasItems("externalCode", "plate", "serviceIntervalKm", "nextServiceAtKm")))
                .andExpect(jsonPath("$.components.schemas.CreateVehicleRequest.properties.externalCode.maxLength")
                        .value(64))
                .andExpect(jsonPath("$.components.schemas.CreateVehicleRequest.properties.plate.maxLength")
                        .value(16))
                .andExpect(jsonPath("$.components.schemas.ChangeVehicleStatusRequest.required")
                        .value(hasItems("status")))
                .andExpect(jsonPath("$.components.schemas.VehicleResponse.properties.status.enum")
                        .value(hasItems("ACTIVE", "DISABLED")))
                .andExpect(jsonPath("$.components.schemas.VehicleResponse.properties.id.format")
                        .value("uuid"))
                .andExpect(jsonPath("$.components.schemas.VehicleResponse.properties.createdAt.format")
                        .value("date-time"));
    }

    /**
     * Verifica che le response di errore riutilizzino lo schema comune.
     */
    @Test
    @DisplayName("Riutilizza ApiErrorResponse per gli errori documentati")
    void reusesApiErrorResponseSchema() throws Exception {
        mockMvc.perform(get(OPEN_API_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.ApiErrorResponse").exists())
                .andExpect(jsonPath("$.components.schemas.ValidationErrorDetail").exists())
                .andExpect(jsonPath("$.components.schemas.ApiErrorResponse.required")
                        .value(hasItems("timestamp", "status", "code", "message", "path", "details")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].post.responses['400'].content['application/json'].schema.$ref"
                ).value(endsWith("/ApiErrorResponse")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles'].post.responses['409'].content['application/json'].schema.$ref"
                ).value(endsWith("/ApiErrorResponse")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}'].get.responses['404'].content['application/json'].schema.$ref"
                ).value(endsWith("/ApiErrorResponse")))
                .andExpect(jsonPath(
                        "$.paths['/api/v1/vehicles/{vehicleId}/status'].patch.responses['503'].content['application/json'].schema.$ref"
                ).value(endsWith("/ApiErrorResponse")));
    }

    /**
     * Verifica la disponibilità dell'entry point e degli asset della Swagger UI.
     */
    @Test
    @DisplayName("Espone Swagger UI")
    void exposesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/swagger-ui/index.html"));

        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
