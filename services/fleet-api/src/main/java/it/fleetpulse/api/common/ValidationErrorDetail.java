package it.fleetpulse.api.common;

public record ValidationErrorDetail(
        String field,
        String message
) {
}
