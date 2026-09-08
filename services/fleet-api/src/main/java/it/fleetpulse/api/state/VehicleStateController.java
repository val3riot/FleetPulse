package it.fleetpulse.api.state;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.fleetpulse.api.common.ApiErrorResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Vehicles")
public class VehicleStateController {
    private final VehicleStateService service;

    public VehicleStateController(VehicleStateService service) {
        this.service = service;
    }

    @Operation(summary = "Restituisce lo stato telemetrico corrente del veicolo")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stato corrente",
            content = @Content(schema = @Schema(implementation = VehicleStateResponse.class))),
        @ApiResponse(responseCode = "400", description = "REQUEST_INVALID",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "404",
            description = "VEHICLE_NOT_FOUND oppure VEHICLE_STATE_NOT_AVAILABLE",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "SERVICE_UNAVAILABLE",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(path = "/api/v1/vehicles/{vehicleId}/state",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public VehicleStateResponse state(@PathVariable UUID vehicleId) {
        return service.findByVehicleId(vehicleId);
    }
}
