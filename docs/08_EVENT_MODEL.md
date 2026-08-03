# Event model

## 1. Topic

| Topic | Key | Producer | Consumer | Scopo |
|---|---|---|---|---|
| `telemetry.raw.v1` | `vehicleId` | Telemetry Gateway | Telemetry Processor | Telemetria pubblicata dal gateway |
| `telemetry.rejected.v1` | `vehicleId` | Telemetry Processor | Operations tooling | Telemetria elaborata ma rifiutata dal dominio |
| `telemetry.dead-letter.v1` | `vehicleId` | Telemetry Processor | Operations tooling | Messaggi non elaborabili o errori tecnici con retry esauriti |

La presenza di `telemetry.rejected.v1` in questo modello definisce il contratto
architetturale. Creazione e configurazione effettiva del topic appartengono alle
ticket Kafka dedicate.

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

1. ricezione e validazione tecnica del contratto;
2. verifica di esistenza e stato del veicolo;
3. per un veicolo `ACTIVE`, inizio transaction e inserimento idempotente;
4. generazione degli alert;
5. commit PostgreSQL;
6. aggiornamento Redis;
7. avanzamento dell'offset.

Per un veicolo sconosciuto o `DISABLED`, il processor non avvia i side effect
di dominio e segue il flusso di rifiuto asincrono descritto nella sezione 7.

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

La classificazione non retryable evita di ripetere indefinitamente la stessa
elaborazione. Prima di avanzare l'offset, il relativo esito deve essere reso
osservabile su `telemetry.rejected.v1` oppure `telemetry.dead-letter.v1`, secondo
la natura del problema.

## 7. Telemetry rejection event

`UNKNOWN_VEHICLE` e `VEHICLE_DISABLED` sono rifiuti permanenti di dominio. Non
producono righe in `telemetry_samples`, aggiornamenti Redis o alert. Producono
log strutturati, metriche distinte per `reason` e un evento su
`telemetry.rejected.v1`.

Contratto minimo concettuale:

```json
{
  "messageId": "dc0fc799-0913-4e72-bd2d-8ee8ccf52e22",
  "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
  "reason": "UNKNOWN_VEHICLE",
  "rejectedAt": "2026-08-01T10:16:00Z",
  "sourceTopic": "telemetry.raw.v1",
  "sourcePartition": 1,
  "sourceOffset": 1254
}
```

L'elaborazione è conclusa soltanto dopo che il rifiuto è stato pubblicato. Se
la pubblicazione fallisce, l'errore è tecnico: si applica la policy di retry e
l'offset non deve essere avanzato nascondendo la perdita dell'esito.

## 8. Dead-letter event

`telemetry.dead-letter.v1` non contiene rifiuti di eligibility. È riservato a
payload Kafka non deserializzabili, contratti incompatibili e fallimenti tecnici
che hanno esaurito la politica di retry.

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

## 9. Schema evolution

- nuovi campi additive preferibilmente opzionali;
- breaking change con nuova versione;
- campi opzionali sconosciuti ignorati;
- major version non supportate rifiutate esplicitamente;
- compatibilità coperta dai test.
