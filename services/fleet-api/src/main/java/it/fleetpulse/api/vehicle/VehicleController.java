package it.fleetpulse.api.vehicle;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.fleetpulse.api.common.ApiErrorResponse;
import it.fleetpulse.api.common.PagedResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@Tag(
        name = "Vehicles",
        description = "Registrazione, consultazione e gestione dei veicoli"
)
@RestController
@RequestMapping("/api/v1")
public class VehicleController {
    private final VehicleService vehicleService;
    private final VehiclePageableFactory vehiclePageableFactory;

    /**
     * Crea il controller con il servizio dei veicoli.
     */
    public VehicleController(VehicleService vehicleService, VehiclePageableFactory vehiclePageableFactory) {
        this.vehicleService = vehicleService;
        this.vehiclePageableFactory = vehiclePageableFactory;
    }

    /**
     * Registra un veicolo e restituisce la risorsa creata con il relativo URI.
     */
    @Operation(summary = "Registra un nuovo veicolo")
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Veicolo registrato",
                    headers = @Header(
                            name = "Location",
                            description = "URI del veicolo creato",
                            schema = @Schema(type = "string", format = "uri")
                    ),
                    content = @Content(schema = @Schema(implementation = VehicleResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Richiesta non valida",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Codice esterno o targa già assegnati",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "415", description = "Media type non supportato",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Errore interno",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Servizio temporaneamente non disponibile",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping(
            path = "/vehicles",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<VehicleResponse> create(@RequestBody @Valid CreateVehicleRequest request) {
        VehicleResponse vehicle = vehicleService.create(request);
        URI location = ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(vehicle.id())
                .toUri();
        return ResponseEntity
                .created(location)
                .body(vehicle);
    }

    /**
     * Restituisce il dettaglio di un veicolo identificato dal suo UUID.
     */
    @Operation(summary = "Restituisce il dettaglio di un veicolo")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Dettaglio del veicolo",
                    content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
            @ApiResponse(responseCode = "400", description = "UUID non valido",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Veicolo non trovato",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Errore interno",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Servizio temporaneamente non disponibile",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(
            path = "/vehicles/{vehicleId}",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public VehicleResponse getById(
            @Parameter(description = "Identificativo UUID del veicolo", required = true)
            @PathVariable UUID vehicleId
    ) {
        return vehicleService.findById(vehicleId);
    }

    /**
     * Restituisce una pagina di veicoli filtrata e ordinata.
     */
    @Operation(summary = "Elenca e filtra i veicoli")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina di veicoli"),
            @ApiResponse(responseCode = "400", description = "Filtri o paginazione non validi",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Errore interno",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Servizio temporaneamente non disponibile",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping(
            path = "/vehicles",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public PagedResponse<VehicleResponse> findVehicles(
            @Parameter(description = "Ricerca case-insensitive per codice esterno o targa")
            @RequestParam(required = false) String query,
            @Parameter(description = "Stato del veicolo")
            @RequestParam(required = false) VehicleStatus status,
            @Parameter(schema = @Schema(type = "integer", minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(schema = @Schema(type = "integer", minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(defaultValue = "20") int size,
            @Parameter(
                    description = "Un solo criterio <field>,<direction>; campi: createdAt, externalCode, plate, status",
                    schema = @Schema(type = "string", defaultValue = "createdAt,desc")
            )
            @RequestParam(defaultValue = "createdAt,desc") String sort
    ) {
        Pageable pageable = vehiclePageableFactory.create(
                page,
                size,
                sort
        );

        VehicleSearchCriteria criteria =
                new VehicleSearchCriteria(query, status);
        return vehicleService.search(criteria, pageable);
    }

    /**
     * Modifica lo stato di un veicolo.
     */
    @Operation(summary = "Modifica lo stato di un veicolo")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Veicolo aggiornato",
                    content = @Content(schema = @Schema(implementation = VehicleResponse.class))),
            @ApiResponse(responseCode = "400", description = "UUID o body non validi",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Veicolo non trovato",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "415", description = "Media type non supportato",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Errore interno",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Servizio temporaneamente non disponibile",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PatchMapping(
            path = "/vehicles/{vehicleId}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public VehicleResponse changeStatus(
            @Parameter(description = "Identificativo UUID del veicolo", required = true)
            @PathVariable UUID vehicleId,
            @RequestBody @Valid ChangeVehicleStatusRequest request
    ) {
        return vehicleService.changeStatus(vehicleId, request);
    }
}
