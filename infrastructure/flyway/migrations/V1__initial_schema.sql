CREATE TABLE vehicles
(
    id                  UUID        NOT NULL,
    external_code       VARCHAR(64) NOT NULL,
    plate               VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL,
    service_interval_km INTEGER     NOT NULL,
    next_service_at_km  BIGINT      NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_vehicles PRIMARY KEY (id),
    CONSTRAINT uq_vehicles_external_code UNIQUE (external_code),
    CONSTRAINT uq_vehicles_plate UNIQUE (plate),
    CONSTRAINT ck_vehicles_external_code_not_blank
        CHECK (LENGTH(TRIM(external_code)) > 0),
    CONSTRAINT ck_vehicles_plate_not_blank
        CHECK (LENGTH(TRIM(plate)) > 0),
    CONSTRAINT ck_vehicles_status
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_vehicles_service_interval_km
        CHECK (service_interval_km > 0),
    CONSTRAINT ck_vehicles_next_service_at_km
        CHECK (next_service_at_km >= 0)
);

CREATE TABLE telemetry_samples
(
    id                       BIGINT GENERATED ALWAYS AS IDENTITY,
    message_id               UUID             NOT NULL,
    vehicle_id               UUID             NOT NULL,
    sequence_number          BIGINT           NOT NULL,
    observed_at              TIMESTAMPTZ      NOT NULL,
    received_at              TIMESTAMPTZ      NOT NULL,
    processed_at             TIMESTAMPTZ      NOT NULL,
    speed_kmh                DOUBLE PRECISION NOT NULL,
    engine_temperature_c     DOUBLE PRECISION NOT NULL,
    battery_voltage          DOUBLE PRECISION NOT NULL,
    odometer_km              BIGINT           NOT NULL,
    latitude                 DOUBLE PRECISION NOT NULL,
    longitude                DOUBLE PRECISION NOT NULL,

    CONSTRAINT pk_telemetry_samples PRIMARY KEY (id),
    CONSTRAINT uq_telemetry_samples_message_id UNIQUE (message_id),
    CONSTRAINT uq_telemetry_samples_message_vehicle
        UNIQUE (message_id, vehicle_id),
    CONSTRAINT fk_telemetry_samples_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicles (id),
    CONSTRAINT ck_telemetry_samples_sequence_number
        CHECK (sequence_number >= 0),
    CONSTRAINT ck_telemetry_samples_speed_kmh
        CHECK (
            speed_kmh >= 0
            AND speed_kmh NOT IN (
                'NaN'::DOUBLE PRECISION,
                'Infinity'::DOUBLE PRECISION,
                '-Infinity'::DOUBLE PRECISION
            )
        ),
    CONSTRAINT ck_telemetry_samples_engine_temperature_c
        CHECK (
            engine_temperature_c NOT IN (
                'NaN'::DOUBLE PRECISION,
                'Infinity'::DOUBLE PRECISION,
                '-Infinity'::DOUBLE PRECISION
            )
        ),
    CONSTRAINT ck_telemetry_samples_battery_voltage
        CHECK (
            battery_voltage >= 0
            AND battery_voltage NOT IN (
                'NaN'::DOUBLE PRECISION,
                'Infinity'::DOUBLE PRECISION,
                '-Infinity'::DOUBLE PRECISION
            )
        ),
    CONSTRAINT ck_telemetry_samples_odometer_km
        CHECK (odometer_km >= 0),
    CONSTRAINT ck_telemetry_samples_latitude
        CHECK (
            latitude BETWEEN -90 AND 90
            AND latitude NOT IN (
                'NaN'::DOUBLE PRECISION,
                'Infinity'::DOUBLE PRECISION,
                '-Infinity'::DOUBLE PRECISION
            )
        ),
    CONSTRAINT ck_telemetry_samples_longitude
        CHECK (
            longitude BETWEEN -180 AND 180
            AND longitude NOT IN (
                'NaN'::DOUBLE PRECISION,
                'Infinity'::DOUBLE PRECISION,
                '-Infinity'::DOUBLE PRECISION
            )
        )
);

CREATE INDEX ix_telemetry_samples_vehicle_observed_at
    ON telemetry_samples (vehicle_id, observed_at DESC, id DESC);

CREATE INDEX ix_telemetry_samples_vehicle_sequence_number
    ON telemetry_samples (vehicle_id, sequence_number DESC, id DESC);

CREATE TABLE maintenance_alerts
(
    id                UUID         NOT NULL,
    vehicle_id        UUID         NOT NULL,
    source_message_id UUID         NOT NULL,
    type              VARCHAR(32)  NOT NULL,
    severity          VARCHAR(16)  NOT NULL,
    description       VARCHAR(255) NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    acknowledged_at   TIMESTAMPTZ,
    closed_at         TIMESTAMPTZ,

    CONSTRAINT pk_maintenance_alerts PRIMARY KEY (id),
    CONSTRAINT uq_maintenance_alerts_source_message_type
        UNIQUE (source_message_id, type),
    CONSTRAINT fk_maintenance_alerts_source_sample
        FOREIGN KEY (source_message_id, vehicle_id)
            REFERENCES telemetry_samples (message_id, vehicle_id),
    CONSTRAINT ck_maintenance_alerts_status
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'CLOSED')),
    CONSTRAINT ck_maintenance_alerts_type_not_blank
        CHECK (LENGTH(TRIM(type)) > 0),
    CONSTRAINT ck_maintenance_alerts_severity_not_blank
        CHECK (LENGTH(TRIM(severity)) > 0),
    CONSTRAINT ck_maintenance_alerts_description
        CHECK (LENGTH(TRIM(description)) > 0),
    CONSTRAINT ck_maintenance_alerts_timestamps
        CHECK (
            (status = 'OPEN' AND acknowledged_at IS NULL AND closed_at IS NULL)
            OR (status = 'ACKNOWLEDGED' AND acknowledged_at IS NOT NULL AND closed_at IS NULL)
            OR (status = 'CLOSED' AND closed_at IS NOT NULL)
        ),
    CONSTRAINT ck_maintenance_alerts_acknowledged_at
        CHECK (acknowledged_at IS NULL OR acknowledged_at >= created_at),
    CONSTRAINT ck_maintenance_alerts_closed_at
        CHECK (
            closed_at IS NULL
            OR closed_at >= COALESCE(acknowledged_at, created_at)
        )
);

CREATE INDEX ix_maintenance_alerts_vehicle_created_at
    ON maintenance_alerts (vehicle_id, created_at DESC, id DESC);

CREATE INDEX ix_maintenance_alerts_status_created_at
    ON maintenance_alerts (status, created_at DESC, id DESC);
