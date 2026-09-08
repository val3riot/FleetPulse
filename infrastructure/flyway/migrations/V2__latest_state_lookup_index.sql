CREATE INDEX ix_telemetry_samples_vehicle_latest_state
    ON telemetry_samples (vehicle_id, observed_at DESC, sequence_number DESC, id DESC);
