# Data model

## 1. Schema persistente

Lo schema autorevole è creato da Flyway tramite
`infrastructure/flyway/migrations/V1__initial_schema.sql`. I frammenti seguenti
riassumono il contratto persistente e devono rimanere coerenti con la migration.

### `vehicles`

```sql
create table vehicles (
    id uuid not null,
    external_code varchar(64) not null,
    plate varchar(16) not null,
    status varchar(16) not null,
    service_interval_km integer not null,
    next_service_at_km bigint not null,
    created_at timestamptz not null,

    constraint pk_vehicles primary key (id),
    constraint uq_vehicles_external_code unique (external_code),
    constraint uq_vehicles_plate unique (plate),
    constraint ck_vehicles_external_code_not_blank
        check (length(trim(external_code)) > 0),
    constraint ck_vehicles_plate_not_blank
        check (length(trim(plate)) > 0),
    constraint ck_vehicles_status
        check (status in ('ACTIVE', 'DISABLED')),
    constraint ck_vehicles_service_interval_km
        check (service_interval_km > 0),
    constraint ck_vehicles_next_service_at_km
        check (next_service_at_km >= 0)
);
```

Lo stato iniziale `ACTIVE` viene assegnato dall'applicazione. Il database non
usa un default implicito: ogni insert deve valorizzare esplicitamente la colonna.

### `telemetry_samples`

```sql
create table telemetry_samples (
    id bigint generated always as identity,
    message_id uuid not null,
    vehicle_id uuid not null,
    sequence_number bigint not null,
    observed_at timestamptz not null,
    received_at timestamptz not null,
    processed_at timestamptz not null,
    speed_kmh double precision not null,
    engine_temperature_c double precision not null,
    battery_voltage double precision not null,
    odometer_km bigint not null,
    latitude double precision not null,
    longitude double precision not null,

    constraint pk_telemetry_samples primary key (id),
    constraint uq_telemetry_samples_message_id unique (message_id),
    constraint uq_telemetry_samples_message_vehicle
        unique (message_id, vehicle_id),
    constraint fk_telemetry_samples_vehicle
        foreign key (vehicle_id) references vehicles (id)
);
```

La migration aggiunge inoltre check constraint per:

- `sequence_number >= 0`;
- velocità e tensione non negative;
- odometro non negativo;
- latitudine tra `-90` e `90`;
- longitudine tra `-180` e `180`;
- esclusione di `NaN`, `Infinity` e `-Infinity` da tutti i valori floating-point.

Indici:

```sql
create index ix_telemetry_samples_vehicle_observed_at
    on telemetry_samples (vehicle_id, observed_at desc, id desc);

create index ix_telemetry_samples_vehicle_sequence_number
    on telemetry_samples (vehicle_id, sequence_number desc, id desc);
```

L'ordinamento aggiuntivo per `id` rende deterministica la paginazione quando più
sample hanno lo stesso timestamp o sequence number.

### `maintenance_alerts`

```sql
create table maintenance_alerts (
    id uuid not null,
    vehicle_id uuid not null,
    source_message_id uuid not null,
    type varchar(32) not null,
    severity varchar(16) not null,
    description varchar(255) not null,
    status varchar(16) not null,
    created_at timestamptz not null,
    acknowledged_at timestamptz,
    closed_at timestamptz,

    constraint pk_maintenance_alerts primary key (id),
    constraint uq_maintenance_alerts_source_message_type
        unique (source_message_id, type),
    constraint fk_maintenance_alerts_source_sample
        foreign key (source_message_id, vehicle_id)
            references telemetry_samples (message_id, vehicle_id)
);
```

La foreign key composita garantisce che il sample sorgente appartenga allo
stesso veicolo dell'alert. La migration vincola inoltre:

- `status` a `OPEN`, `ACKNOWLEDGED` o `CLOSED`;
- `type`, `severity` e `description` a valori non blank;
- coerenza tra stato e timestamp;
- `acknowledged_at >= created_at`;
- `closed_at >= coalesce(acknowledged_at, created_at)`.

Indici:

```sql
create index ix_maintenance_alerts_vehicle_created_at
    on maintenance_alerts (vehicle_id, created_at desc, id desc);

create index ix_maintenance_alerts_status_created_at
    on maintenance_alerts (status, created_at desc, id desc);
```

## 2. Vincoli di idempotency

```text
telemetry_samples.message_id UNIQUE
maintenance_alerts(source_message_id, type) UNIQUE
```

Il doppio unique su `telemetry_samples` è intenzionale:

- `message_id` garantisce l'idempotenza globale;
- `(message_id, vehicle_id)` supporta la foreign key composita degli alert.

Un uniqueness conflict atteso durante il replay viene classificato come
duplicate result e non genera un secondo side effect.

## 3. Transaction model

La transaction PostgreSQL del processor include:

1. inserimento idempotente del sample;
2. persistenza degli alert derivati.

L'aggiornamento Redis avviene dopo la transaction autorevole. Un errore Redis
non annulla il commit PostgreSQL.

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

Non esiste una tabella `vehicle_state`: lo stato corrente è una projection Redis
ricostruibile dallo storico PostgreSQL.

## 5. Time model

Tutti i timestamp sono UTC.

- `observedAt`: creato dal simulator;
- `receivedAt`: assegnato dal gateway;
- `processedAt`: assegnato dal processor;
- `createdAt` dell'alert: assegnato quando la regola genera l'alert.

## 6. Migration policy

- ogni modifica ha una nuova migration Flyway;
- le migration pubblicate non vengono riscritte;
- le modifiche distruttive richiedono un compatibility plan;
- Flyway applica lo schema prima dell'avvio dei servizi dipendenti;
- Hibernate usa `ddl-auto=validate` e non modifica lo schema;
- le entity Java vengono introdotte nelle ticket applicative che leggono o
  scrivono le rispettive tabelle.
