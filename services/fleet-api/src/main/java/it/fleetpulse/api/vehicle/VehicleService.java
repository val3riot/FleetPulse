package it.fleetpulse.api.vehicle;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.PagedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class VehicleService {
    private static final Logger log = LoggerFactory.getLogger(VehicleService.class);

    private final VehicleRepository vehicleRepository;
    private final VehicleMapper vehicleMapper;
    private final Clock clock;

    /**
     * Crea il servizio con repository, mapper e sorgente temporale applicativa.
     */
    public VehicleService(VehicleRepository vehicleRepository, VehicleMapper vehicleMapper,
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
        boolean externalCodeConflict =
            vehicleRepository.existsByExternalCode(request.externalCode());
        if (externalCodeConflict) {
            throw new ApplicationException(ErrorCode.VEHICLE_EXTERNAL_CODE_CONFLICT);
        }
        boolean plateConflict = vehicleRepository.existsByPlate(request.plate());
        if (plateConflict) {
            throw new ApplicationException(ErrorCode.VEHICLE_PLATE_CONFLICT);
        }
        VehicleEntity entity =
            vehicleMapper.toEntity(request, clock.instant(), VehicleStatus.ACTIVE);
        entity = vehicleRepository.save(entity);
        log.info("Vehicle registered: vehicleId={}, status={}", entity.getId(), entity.getStatus());
        return vehicleMapper.toResponse(entity);
    }

    /**
     * Restituisce il dettaglio del veicolo o segnala che non esiste.
     */
    @Transactional(readOnly = true)
    public VehicleResponse findById(UUID id) {
        log.debug("Looking up vehicle: vehicleId={}", id);
        VehicleEntity entity = vehicleRepository.findById(id)
            .orElseThrow(() -> new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));
        return vehicleMapper.toResponse(entity);
    }

    /**
     * Cerca i veicoli applicando filtri, paginazione e ordinamento richiesti.
     */
    @Transactional(readOnly = true)
    public PagedResponse<VehicleResponse> search(VehicleSearchCriteria criteria,
        Pageable pageable) {
        Page<VehicleEntity> result =
            vehicleRepository.findAll(VehicleSpecifications.from(criteria), pageable);
        log.debug("Vehicle search completed: page={}, size={}, results={}, total={}",
            pageable.getPageNumber(), pageable.getPageSize(), result.getNumberOfElements(),
            result.getTotalElements());

        return PagedResponse.from(result, vehicleMapper::toResponse);
    }

    /**
     * Modifica lo stato di un veicolo.
     */
    @Transactional
    public VehicleResponse changeStatus(UUID id, ChangeVehicleStatusRequest request) {
        VehicleEntity entity = vehicleRepository.findById(id)
            .orElseThrow(() -> new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND));
        VehicleStatus previousStatus = entity.getStatus();
        entity.changeStatus(request.status());
        entity = vehicleRepository.save(entity);
        log.info("Vehicle status updated: vehicleId={}, previousStatus={}, currentStatus={}", id,
            previousStatus, entity.getStatus());
        return vehicleMapper.toResponse(entity);
    }
}
