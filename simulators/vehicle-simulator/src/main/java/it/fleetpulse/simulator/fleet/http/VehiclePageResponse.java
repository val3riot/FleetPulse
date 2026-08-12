package it.fleetpulse.simulator.fleet.http;

import java.util.List;

public record VehiclePageResponse(
        List<VehicleResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
}
