package it.fleetpulse.api.telemetry;

import it.fleetpulse.api.telemetry.persistence.TelemetrySampleEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Converte il read model PostgreSQL nel contratto REST dello storico.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TelemetrySampleMapper {
    TelemetrySampleResponse toResponse(TelemetrySampleEntity entity);
}
