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
fleetpulse_gateway_frames_received_total
fleetpulse_gateway_frames_rejected_total
fleetpulse_gateway_publish_failures_total
fleetpulse_gateway_ack_latency
```

### Processor

```text
fleetpulse_processor_events_total
fleetpulse_processor_duplicates_total
fleetpulse_processor_failures_total
fleetpulse_processor_dead_letter_total
fleetpulse_processing_latency
fleetpulse_redis_update_failures_total
```

### Fleet API

```text
fleetpulse_api_cache_hits_total
fleetpulse_api_cache_misses_total
fleetpulse_api_cache_fallback_total
fleetpulse_api_request_latency
```

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
4. persistenza;
5. aggiornamento Redis;
6. eventuale alert.
