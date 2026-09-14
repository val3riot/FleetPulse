ALTER TABLE maintenance_alerts
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
