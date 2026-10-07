package it.fleetpulse.api.dashboard;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.fleetpulse.api.common.ApiErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dashboard", description = "Panoramica funzionale della flotta")
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @Operation(summary = "Restituisce la panoramica della flotta",
        description = "Snapshot PostgreSQL senza dipendenza Redis; " +
            "finestra e limite configurati dal servizio")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Panoramica della flotta",
            content = @Content(schema = @Schema(implementation = DashboardResponse.class))),
        @ApiResponse(responseCode = "500", description = "Errore interno",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
        @ApiResponse(responseCode = "503", description = "Servizio non disponibile",
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public DashboardResponse getDashboard() {
        return service.getDashboard();
    }
}
