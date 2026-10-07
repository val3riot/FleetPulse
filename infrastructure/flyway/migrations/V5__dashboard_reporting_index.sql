-- Dashboard: filtro temporale globale e conteggio dei veicoli distinti.
-- Gli indici con vehicle_id iniziale servono le query scoped, non questa finestra.
CREATE INDEX ix_telemetry_samples_observed_at_vehicle
    ON telemetry_samples (observed_at, vehicle_id);
