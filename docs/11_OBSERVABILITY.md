# Observability

## 1. Obiettivi

I segnali operativi devono permettere di rispondere a:

- I servizi sono vivi e pronti?
- I client si stanno collegando?
- I frame vengono rifiutati?
- Il processor tiene il passo?
- Esistono eventi duplicati?
- Il fallback Redis è attivo?
- Quanto dura la persistenza?
- Dove si è fermato un `messageId`?

## 2. Structured logging

Campi consigliati:

| Campo | Significato |
|---|---|
| `timestamp` | Timestamp UTC |
| `level` | Log level |
| `service` | Servizio |
| `event` | Nome stabile dell'evento |
| `messageId` | Correlation ID |
| `vehicleId` | Identificativo veicolo |
| `sequenceNumber` | Sequenza |
| `connectionId` | Connessione gateway |
| `topic` | Kafka topic |
| `partition` | Kafka partition |
| `offset` | Kafka offset |
| `durationMs` | Durata |
| `errorCode` | Classificazione dell'errore |

Il payload completo non deve essere loggato di default.

## 3. Metriche

### Gateway

```text
fleetpulse_gateway_connections_active
fleetpulse_gateway_connections_accepted_total
fleetpulse_gateway_connections_rejected_total
fleetpulse_gateway_tcp_connections_capacity_rejected_total
fleetpulse_gateway_connections_timeouts_total
fleetpulse_gateway_connections_failures_total
fleetpulse_gateway_frames_received_total
fleetpulse_gateway_frames_rejected_total
fleetpulse_gateway_publish_failures_total
fleetpulse_gateway_ack_latency
```

`fleetpulse_gateway_connections_rejected_total` conta le connessioni rifiutate
durante il dispatch, mentre
`fleetpulse_gateway_tcp_connections_capacity_rejected_total` conta le
connessioni rifiutate perché è stato raggiunto il limite configurato dal
gateway.

### Processor

```text
fleetpulse_processor_events_total
fleetpulse_processor_duplicates_total
fleetpulse_processor_failures_total
fleetpulse_processor_failures_terminal_total
fleetpulse_processor_rejections_total{reason="UNKNOWN_VEHICLE"}
fleetpulse_processor_rejections_total{reason="VEHICLE_DISABLED"}
fleetpulse_processor_dead_letter_total
fleetpulse_processing_latency
fleetpulse_redis_update_failures_total
```

Per la latest-state projection, il contatore applicativo
`fleetpulse.telemetry.latest_state.updates` usa solo il tag `outcome`, con valori
`updated`, `skipped`, `failed`. Ogni tentativo incrementa un solo esito.
`fleetpulse_redis_update_failures_total` resta nel catalogo e viene incrementato
anche per ciascun esito `failed`, senza tag dinamici.

Gli esiti `UPDATED` e `SKIPPED` producono log `DEBUG`; `FAILED` produce `WARN`
con frequenza limitata durante guasti prolungati. Il limite dei log non riduce
il conteggio delle metriche. Identificativi di veicolo, messaggio e sequenza
possono comparire nei log, mai nei tag. Contratto e verifiche in
[ADR-009 — Contratto e aggiornamento della latest-state projection](adr/ADR-009-LATEST-STATE-PROJECTION.md).

### Fleet API

```text
fleetpulse_api_cache_hits_total
fleetpulse_api_cache_misses_total
fleetpulse_api_cache_fallback_total
fleetpulse_api_cache_failures_total
fleetpulse_api_cache_repair_failures_total
fleetpulse_api_request_latency
```

FP-031 implementa i cinque contatori cache senza tag dinamici. `misses` conta
solo chiavi assenti, `failures` errori di lettura/decodifica; `fallback` conta ogni
accesso al percorso PostgreSQL, anche senza sample o in errore. Il repair fallito
incrementa `repair_failures`. I warning applicativi sono limitati a uno ogni
30 secondi per istanza senza payload o stacktrace; i contatori restano completi.
Si veda [ADR-010](adr/ADR-010-STATE-API-FALLBACK.md).

## 4. Health

### Liveness

Indica che il processo applicativo è in esecuzione.

### Readiness

Indica che il servizio può svolgere la propria funzione primaria.

Esempi:

- Fleet API può essere ready con Redis down se PostgreSQL è disponibile;
- il processor non è ready a persistere se PostgreSQL è down;
- il gateway dipende dalla disponibilità del listener e dalla capacità di pubblicare.

## 5. Dashboard

Sezioni consigliate:

1. active connections;
2. frame ricevuti e rifiutati;
3. processing rate;
4. duplicati e failure;
5. processing latency percentiles;
6. cache hit/fallback rate;
7. JVM memory e thread;
8. dependency health.

## 6. Correlazione

Per un `messageId` devono essere individuabili:

1. accettazione nel gateway;
2. risultato della pubblicazione;
3. consumo;
4. eventuale rifiuto di dominio;
5. persistenza;
6. aggiornamento Redis;
7. eventuale alert o dead-letter.

## ADR di riferimento

- [ADR-005 — Redis come cache ricostruibile](adr/ADR-005-REDIS-CACHE-RICOSTRUIBILE.md)
- [ADR-006 — At-least-once con application idempotency](adr/ADR-006-AT-LEAST-ONCE-E-IDEMPOTENCY.md)
- [ADR-007 — Validazione del veicolo nel telemetry processor](adr/ADR-007-VALIDAZIONE-VEICOLO.md)

- [ADR-009 — Contratto e aggiornamento della latest-state projection](adr/ADR-009-LATEST-STATE-PROJECTION.md)
