package it.fleetpulse.api.state;

@FunctionalInterface
public interface LatestStateProjection {
    ProjectionUpdateResult updateIfNewer(LatestVehicleState candidate);

}
