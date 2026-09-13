package it.fleetpulse.api.alert;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.fleetpulse.api.common.ApiErrorResponse;
import it.fleetpulse.api.common.PagedResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Maintenance alerts", description = "Consultazione degli alert di manutenzione")
@RestController
@RequestMapping("/api/v1")
public class MaintenanceAlertController {
    private final MaintenanceAlertService service;

    public MaintenanceAlertController(MaintenanceAlertService service) {
        this.service = service;
    }

    @Operation(summary = "Elenca gli alert di un veicolo")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pagina di alert"),
        @ApiResponse(responseCode = "400", description = "Filtri non validi",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Veicolo non trovato",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Errore interno",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Servizio non disponibile",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(path = "/vehicles/{vehicleId}/alerts",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public PagedResponse<MaintenanceAlertResponse> findByVehicleId(
        @Parameter(required = true, description = "Identificativo UUID del veicolo")
        @PathVariable UUID vehicleId,
        @Valid @ParameterObject @ModelAttribute MaintenanceAlertSearchRequest request) {
        return service.findByVehicleId(vehicleId, request);
    }

    @Operation(summary = "Elenca e filtra gli alert")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pagina di alert"),
        @ApiResponse(responseCode = "400", description = "Filtri non validi",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Errore interno",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Servizio non disponibile",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(path = "/alerts", produces = MediaType.APPLICATION_JSON_VALUE)
    public PagedResponse<MaintenanceAlertResponse> search(
        @Parameter(description = "Identificativo UUID del veicolo")
        @RequestParam(required = false) UUID vehicleId,
        @Valid @ParameterObject @ModelAttribute MaintenanceAlertSearchRequest request) {
        return service.search(vehicleId, request);
    }

    @Operation(summary = "Restituisce il dettaglio di un alert")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Dettaglio dell'alert",
            content = @Content(schema = @Schema(implementation = MaintenanceAlertResponse.class))),
        @ApiResponse(responseCode = "400", description = "UUID non valido",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "Alert non trovato",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "Errore interno",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Servizio non disponibile",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(path = "/alerts/{alertId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public MaintenanceAlertResponse findById(
        @Parameter(required = true, description = "Identificativo UUID dell'alert")
        @PathVariable UUID alertId) {
        return service.findById(alertId);
    }
}
