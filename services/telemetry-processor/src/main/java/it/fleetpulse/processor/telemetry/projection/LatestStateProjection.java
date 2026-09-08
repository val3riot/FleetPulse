package it.fleetpulse.processor.telemetry.projection;

public interface LatestStateProjection {
    ProjectionUpdateResult updateIfNewer(LatestVehicleState candidate);

}
