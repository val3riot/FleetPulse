# Event model

## 1. Topic

| Topic | Key | Producer | Consumer | Scopo |
|---|---|---|---|---|
| `telemetry.raw.v1` | `vehicleId` | Telemetry Gateway | Telemetry Processor | Telemetria accettata |
| `telemetry.dead-letter.v1` | `vehicleId` | Telemetry Processor | Operations tooling | Eventi permanentemente falliti |

## 2. Event schema

```json
{
  "eventVersion": 1,
  "messageId": "dc0fc799-0913-4e72-bd2d-8ee8ccf52e22",
  "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
  "sequenceNumber": 42,
  "observedAt": "2026-08-01T10:15:30Z",
  "receivedAt": "2026-08-01T10:15:30.083Z",
  "telemetry": {
    "speedKmh": 72.4,
    "engineTemperatureC": 91.8,
    "batteryVoltage": 12.6,
    "odometerKm": 85312,
    "latitude": 41.9028,
    "longitude": 12.4964
  }
}
```

## 3. Partitioning

La Kafka record key è `vehicleId`.

Conseguenze:

- tutti gli eventi dello stesso veicolo nella stessa partition;
- ordine relativo preservato;
- veicoli differenti elaborabili in parallelo;
- nessun ordering globale.

## 4. Delivery semantics

FleetPulse assume at-least-once.

Un evento può essere riconsegnato quando:

- l'elaborazione è terminata ma l'offset non è avanzato;
- il consumer si riavvia;
- avviene un rebalance;
- un errore transitorio produce retry.

`messageId` è l'idempotency key.

## 5. Sequenza del consumer

1. ricezione;
2. validazione;
3. inizio transaction;
4. inserimento idempotente;
5. generazione degli alert;
6. commit;
7. aggiornamento Redis;
8. avanzamento del consumer.

## 6. Classificazione dei retry

### Retryable

- database temporaneamente irraggiungibile;
- DNS failure temporaneo;
- interruzione di rete;
- broker/client exception recuperabile.

### Non retryable

- event version non supportata;
- identificativo malformato;
- valore di dominio impossibile;
- violazione permanente di un vincolo.

## 7. Dead-letter event

```json
{
  "failedAt": "2026-08-01T10:16:00Z",
  "sourceTopic": "telemetry.raw.v1",
  "sourcePartition": 1,
  "sourceOffset": 1254,
  "attempts": 3,
  "errorCode": "UNSUPPORTED_EVENT_VERSION",
  "errorMessage": "Unsupported eventVersion: 99",
  "originalKey": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
  "originalPayload": {}
}
```

## 8. Schema evolution

- nuovi campi additive preferibilmente opzionali;
- breaking change con nuova versione;
- campi opzionali sconosciuti ignorati;
- major version non supportate rifiutate esplicitamente;
- compatibilità coperta dai test.
