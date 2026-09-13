package it.fleetpulse.api.alert;

import it.fleetpulse.api.common.ApplicationException;
import it.fleetpulse.api.common.ErrorCode;
import it.fleetpulse.api.common.PagedResponse;
import it.fleetpulse.api.vehicle.VehicleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class MaintenanceAlertService {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceAlertService.class);

    private final MaintenanceAlertRepository alerts;
    private final VehicleRepository vehicles;
    private final MaintenanceAlertMapper mapper;
    private final MaintenanceAlertPageableFactory pageableFactory;
    private final MaintenanceAlertRequestValidator requestValidator;

    public MaintenanceAlertService(MaintenanceAlertRepository alerts, VehicleRepository vehicles,
        MaintenanceAlertMapper mapper, MaintenanceAlertPageableFactory pageableFactory,
        MaintenanceAlertRequestValidator requestValidator) {
        this.alerts = alerts;
        this.vehicles = vehicles;
        this.mapper = mapper;
        this.pageableFactory = pageableFactory;
        this.requestValidator = requestValidator;
    }

    @Transactional(readOnly = true)
    public PagedResponse<MaintenanceAlertResponse> findByVehicleId(UUID vehicleId,
        MaintenanceAlertSearchRequest request) {
        requestValidator.validate(request);
        Pageable pageable = pageableFactory.create(request.page(), request.size(), request.sort());

        if (!vehicles.existsById(vehicleId)) {
            throw new ApplicationException(ErrorCode.VEHICLE_NOT_FOUND);
        }

        return search(criteria(vehicleId, request), pageable);
    }

    @Transactional(readOnly = true)
    public PagedResponse<MaintenanceAlertResponse> search(UUID vehicleId,
        MaintenanceAlertSearchRequest request) {
        requestValidator.validate(request);
        Pageable pageable = pageableFactory.create(request.page(), request.size(), request.sort());
        return search(criteria(vehicleId, request), pageable);
    }

    @Transactional(readOnly = true)
    public MaintenanceAlertResponse findById(UUID alertId) {
        MaintenanceAlertEntity alert = alerts.findById(alertId)
            .orElseThrow(() -> new ApplicationException(ErrorCode.ALERT_NOT_FOUND));
        return mapper.toResponse(alert);
    }

    private PagedResponse<MaintenanceAlertResponse> search(
        MaintenanceAlertSearchCriteria criteria, Pageable pageable) {
        Page<MaintenanceAlertEntity> result = alerts.findAll(
            MaintenanceAlertSpecifications.from(criteria), pageable);
        log.debug("Maintenance alert search completed: vehicleId={}, page={}, size={}, " +
                "results={}, total={}", criteria.vehicleId(), pageable.getPageNumber(),
            pageable.getPageSize(), result.getNumberOfElements(), result.getTotalElements());
        return PagedResponse.from(result, mapper::toResponse);
    }

    private MaintenanceAlertSearchCriteria criteria(UUID vehicleId,
        MaintenanceAlertSearchRequest request) {
        return new MaintenanceAlertSearchCriteria(vehicleId, request.status(), request.type(),
            request.severity(), request.from(), request.to());
    }
}
