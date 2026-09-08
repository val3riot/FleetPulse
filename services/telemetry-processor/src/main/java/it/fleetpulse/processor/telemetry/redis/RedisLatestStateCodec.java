package it.fleetpulse.processor.telemetry.redis;

import java.util.Objects;

import org.springframework.stereotype.Component;

import it.fleetpulse.processor.telemetry.projection.LatestStateProjectionException;
import it.fleetpulse.processor.telemetry.projection.LatestVehicleState;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RedisLatestStateCodec {
     private final JsonMapper mapper = JsonMapper.builder()
          .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
          .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

      public String encode(LatestVehicleState state) {
          Objects.requireNonNull(state, "state must not be null");

          RedisLatestVehicleState dto = new RedisLatestVehicleState(
              state.vehicleId(),
              state.lastSequenceNumber(),
              state.lastSeenAt(),
              state.speedKmh(),
              state.engineTemperatureC(),
              state.batteryVoltage(),
              state.odometerKm(),
              state.latitude(),
              state.longitude()
          );

          try {
              return mapper.writeValueAsString(dto);
          } catch (JacksonException failure) {
              throw new LatestStateProjectionException(
                  "Cannot serialize latest vehicle state", failure);
          }
      }

      public LatestVehicleState decode(String json) {
          Objects.requireNonNull(json, "json must not be null");

          RedisLatestVehicleState dto;
          try {
              dto = mapper.readValue(json, RedisLatestVehicleState.class);
          } catch (JacksonException failure) {
              throw new LatestStateProjectionException(
                  "Cannot deserialize latest vehicle state", failure);
          }

          if (dto == null) {
              throw new LatestStateProjectionException(
                  "Latest vehicle state JSON must not be null");
          }

          return new LatestVehicleState(
              dto.vehicleId(),
              dto.lastSequenceNumber(),
              dto.lastSeenAt(),
              dto.speedKmh(),
              dto.engineTemperatureC(),
              dto.batteryVoltage(),
              dto.odometerKm(),
              dto.latitude(),
              dto.longitude()
          );
      }
}
