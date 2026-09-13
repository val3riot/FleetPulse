package it.fleetpulse.api.alert;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

public record MaintenanceAlertSearchRequest(
    AlertStatus status,
    AlertType type,
    AlertSeverity severity,
    @Schema(description = "Inizio incluso dell'intervallo su createdAt") Instant from,
    @Schema(description = "Fine inclusa dell'intervallo su createdAt") Instant to,
    @Min(0) @Schema(minimum = "0", defaultValue = "0") Integer page,
    @Min(1) @Max(100)
    @Schema(minimum = "1", maximum = "100", defaultValue = "50") Integer size,
    @NotBlank @Pattern(regexp = "createdAt,(asc|desc)")
    @Schema(allowableValues = {"createdAt,asc", "createdAt,desc"},
        defaultValue = "createdAt,desc") String sort
) {
    public MaintenanceAlertSearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 50 : size;
        sort = sort == null ? "createdAt,desc" : sort;
    }
}
