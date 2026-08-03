package it.fleetpulse.api.vehicle;

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
    @GetMapping("/vehicles/{vehicleId}")
    public VehicleResponse getById(@PathVariable UUID vehicleId) {
        return vehicleService.findById(vehicleId);
    }

    /**
     * Restituisce una pagina di veicoli filtrata e ordinata.
     */
    @GetMapping(
            path = "/vehicles",
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public PagedResponse<VehicleResponse> findVehicles(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) VehicleStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
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
    @PatchMapping(
            path = "/vehicles/{vehicleId}/status",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public VehicleResponse changeStatus(
            @PathVariable UUID vehicleId,
            @RequestBody @Valid ChangeVehicleStatusRequest request
    ) {
        return vehicleService.changeStatus(vehicleId, request);
    }
}
