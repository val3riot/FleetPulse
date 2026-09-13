package it.fleetpulse.api.telemetry;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.fleetpulse.api.common.ApiErrorResponse;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Vehicles", description = "Registrazione, consultazione e gestione dei veicoli")
@RestController
@RequestMapping("/api/v1")
public class TelemetryHistoryController {

    private final TelemetryHistoryService telemetryHistoryService;

    public TelemetryHistoryController(TelemetryHistoryService telemetryHistoryService) {
        this.telemetryHistoryService = telemetryHistoryService;
    }

    /**
     * Restituisce lo storico telemetrico paginato nell'intervallo UTC inclusivo.
     */
    @Operation(summary = "Restituisce lo storico telemetrico del veicolo")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pagina dello storico telemetrico",
            content = @Content(
                schema = @Schema(implementation = TelemetryHistoryResponse.class))),
        @ApiResponse(responseCode = "400",
            description = "REQUEST_INVALID oppure REQUEST_INVALID_TIME_RANGE",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404", description = "VEHICLE_NOT_FOUND",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(path = "/vehicles/{vehicleId}/telemetry",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public TelemetryHistoryResponse getTelemetryHistory(@PathVariable UUID vehicleId,
        @Valid @ParameterObject @ModelAttribute TelemetryHistoryRequest request) {
        return telemetryHistoryService.findByVehicleId(vehicleId, request);
    }
}
