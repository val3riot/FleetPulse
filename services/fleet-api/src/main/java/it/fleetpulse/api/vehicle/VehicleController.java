package it.fleetpulse.api.vehicle;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class VehicleController {
    private final VehicleService vehicleService;

    /**
     * Crea il controller con il servizio dei veicoli.
     */
    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
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
}
