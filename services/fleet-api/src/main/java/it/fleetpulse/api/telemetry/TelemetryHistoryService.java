package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleEntity;
import it.fleetpulse.api.telemetry.persistence.TelemetrySampleRepository;
import it.fleetpulse.api.vehicle.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TelemetryHistoryService {
    private static final Logger log = LoggerFactory.getLogger(TelemetryHistoryService.class);

    private final VehicleRepository vehicles;
    private final TelemetrySampleRepository samples;
    private final TelemetrySampleMapper mapper;
    private final TelemetryHistoryPageableFactory pageableFactory;
    private final TelemetryHistoryRequestValidator requestValidator;

    public TelemetryHistoryService(VehicleRepository vehicles, TelemetrySampleRepository samples,
        TelemetrySampleMapper mapper, TelemetryHistoryPageableFactory pageableFactory,
        TelemetryHistoryRequestValidator requestValidator) {
        this.vehicles = vehicles;
        this.samples = samples;
        this.mapper = mapper;
        this.pageableFactory = pageableFactory;
        this.requestValidator = requestValidator;
    }

    /**
     * Restituisce la pagina richiesta distinguendo il veicolo assente dallo storico vuoto.
     */
    @Transactional(readOnly = true)
    public TelemetryHistoryResponse findByVehicleId(UUID vehicleId,
        TelemetryHistoryRequest request) {
        requestValidator.validate(request);
        Pageable pageable = pageableFactory.create(request.page(), request.size(), request.sort());

        if (!vehicles.existsById(vehicleId)) {
            throw new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND);
        }

        Page<TelemetrySampleEntity> result = samples.findAllByVehicleIdAndObservedAtBetween(
            vehicleId, request.from(), request.to(), pageable);
        log.debug("Telemetry history query completed: vehicleId={}, page={}, size={}, results={}, " +
                "total={}", vehicleId, pageable.getPageNumber(), pageable.getPageSize(),
            result.getNumberOfElements(), result.getTotalElements());

        return new TelemetryHistoryResponse(result.getContent().stream().map(mapper::toResponse)
            .toList(), result.getNumber(), result.getSize(), result.getTotalElements(),
            result.getTotalPages(), result.isFirst(), result.isLast());
    }
}
