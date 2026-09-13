ALTER TABLE maintenance_alerts
    ADD CONSTRAINT ck_maintenance_alerts_type
        CHECK (type IN (
            'ENGINE_TEMPERATURE_HIGH',
            'BATTERY_VOLTAGE_LOW',
            'SERVICE_DUE'
        )),
    ADD CONSTRAINT ck_maintenance_alerts_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));
