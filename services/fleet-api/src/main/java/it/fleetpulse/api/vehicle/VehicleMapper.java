package it.fleetpulse.api.vehicle;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VehicleMapper {
    /**
     * Converte la richiesta in entity aggiungendo i valori assegnati dal backend.
     */
    VehicleEntity toEntity(CreateVehicleRequest request, Instant createdAt, VehicleStatus status);

    /**
     * Converte l'entity nella risposta REST del veicolo.
     */
    VehicleResponse toResponse(VehicleEntity entity);
}
