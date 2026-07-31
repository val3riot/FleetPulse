# Data model

## 1. Schema persistente

### `vehicles`

```sql
create table vehicles (
    id uuid primary key,
    external_code varchar(64) not null unique,
    plate varchar(16) not null unique,
    status varchar(16) not null,
    service_interval_km integer not null check (service_interval_km > 0),
    next_service_at_km bigint not null check (next_service_at_km >= 0),
    created_at timestamptz not null
);
```

### `telemetry_samples`

```sql
create table telemetry_samples (
    id bigserial primary key,
    message_id uuid not null unique,
    vehicle_id uuid not null references vehicles(id),
    sequence_number bigint not null check (sequence_number >= 0),
    observed_at timestamptz not null,
    received_at timestamptz not null,
    processed_at timestamptz not null,
    speed_kmh double precision not null check (speed_kmh >= 0),
    engine_temperature_c double precision not null,
    battery_voltage double precision not null,
    odometer_km bigint not null check (odometer_km >= 0),
    latitude double precision not null check (latitude between -90 and 90),
    longitude double precision not null check (longitude between -180 and 180)
);
```

Index consigliati:

```sql
create index idx_telemetry_vehicle_observed_at
    on telemetry_samples(vehicle_id, observed_at desc);

create index idx_telemetry_vehicle_sequence
    on telemetry_samples(vehicle_id, sequence_number desc);
```

### `maintenance_alerts`

```sql
create table maintenance_alerts (
    id uuid primary key,
    vehicle_id uuid not null references vehicles(id),
    source_message_id uuid not null,
    type varchar(32) not null,
    severity varchar(16) not null,
    description varchar(255) not null,
    status varchar(16) not null,
    created_at timestamptz not null,
    acknowledged_at timestamptz,
    closed_at timestamptz,
    unique(source_message_id, type)
);
```

## 2. Vincoli di idempotency

```text
telemetry_samples.message_id UNIQUE
maintenance_alerts(source_message_id, type) UNIQUE
```

Un uniqueness conflict atteso durante il replay viene classificato come duplicate result.

## 3. Transaction model

La transaction PostgreSQL include:

1. inserimento del sample;
2. persistenza degli alert derivati.

L'aggiornamento Redis avviene dopo la transaction autorevole.

## 4. Redis model

### Key

```text
vehicle:last:{vehicleId}
```

### Value

```json
{
  "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
  "lastSequenceNumber": 42,
  "lastSeenAt": "2026-08-01T10:15:30Z",
  "speedKmh": 72.4,
  "engineTemperatureC": 91.8,
  "batteryVoltage": 12.6,
  "odometerKm": 85312,
  "latitude": 41.9028,
  "longitude": 12.4964
}
```

### Policy

- TTL configurabile;
- entry ricostruibili;
- perdita della cache non equivalente a perdita dei dati;
- fallback su PostgreSQL;
- freshness derivata da `lastSeenAt`.

## 5. Time model

Tutti i timestamp sono UTC.

- `observedAt`: creato dal simulator;
- `receivedAt`: accettato dal gateway;
- `processedAt`: persistito dal processor.

## 6. Migration policy

- ogni modifica ha una migration Flyway;
- le migration pubblicate non vengono riscritte;
- le modifiche distruttive richiedono un compatibility plan;
- l'applicazione valida la compatibilità dello schema all'avvio.
