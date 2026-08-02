package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class VehicleService {
    private final VehicleRepository vehicleRepository;
    private final VehicleMapper vehicleMapper;
    private final Clock clock;

    /**
     * Crea il servizio con repository, mapper e sorgente temporale applicativa.
     */
    public VehicleService(
            VehicleRepository vehicleRepository,
            VehicleMapper vehicleMapper,
            Clock clock) {
        this.vehicleRepository = vehicleRepository;
        this.vehicleMapper = vehicleMapper;
        this.clock = clock;
    }

    /**
     * Registra un veicolo attivo dopo aver verificato i vincoli applicativi.
     */
    @Transactional
    public VehicleResponse create(CreateVehicleRequest request) {
        boolean externalCodeConflict = vehicleRepository.existsByExternalCode(request.externalCode());
        if (externalCodeConflict) {
            throw new ApplicationException(ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT);
        }
        boolean plateConflict = vehicleRepository.existsByPlate(request.plate());
        if (plateConflict) {
            throw new ApplicationException(ErrorCode.VEHICLE_PLATE_CONFLICT);
        }
        VehicleEntity entity = vehicleMapper.toEntity(request, clock.instant(), VehicleStatus.ACTIVE);
        entity = vehicleRepository.save(entity);
        return vehicleMapper.toResponse(entity);
    }

    /**
     * Restituisce il dettaglio del veicolo o segnala che non esiste.
     */
    @Transactional(readOnly = true)
    public VehicleResponse findById(UUID id) {
        VehicleEntity entity = vehicleRepository.findById(id).orElseThrow(() ->
                new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));
        return vehicleMapper.toResponse(entity);
    }
}
