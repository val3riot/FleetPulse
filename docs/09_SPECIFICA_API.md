# Specifica API

## 1. Convenzioni

Base path:

```text
/api/v1
```

Media type richiesto e restituito:

```text
application/json
```

I timestamp sono rappresentati come stringhe ISO-8601 in UTC, per esempio:

```text
2026-08-01T10:20:00Z
```

Gli identificativi applicativi sono UUID in forma testuale canonica.

L'MVP non implementa autenticazione o autorizzazione. Fleet API è destinata al
solo deployment locale isolato; l'esposizione pubblica richiede una successiva
integrazione di security.

La specifica OpenAPI pubblicata descrive esclusivamente gli endpoint operativi
del modulo veicoli elencati nella sezione 2. Gli endpoint delle sezioni
successive rappresentano il contratto pianificato e non devono essere
considerati disponibili finché le relative ticket non risultano completate.

### 1.1 Error response

Tutti gli errori applicativi e gli errori HTTP gestiti usano la stessa struttura:

```json
{
  "timestamp": "2026-08-01T10:20:00Z",
  "status": 400,
  "code": "REQUEST_INVALID",
  "message": "La richiesta non è valida",
  "path": "/api/v1/vehicles",
  "details": [
    {
      "field": "serviceIntervalKm",
      "message": "deve essere maggiore di zero"
    }
  ]
}
```

Campi:

| Campo | Tipo | Obbligatorio | Significato |
|---|---|---:|---|
| `timestamp` | timestamp UTC | sì | Istante di generazione dell'errore |
| `status` | integer | sì | HTTP status numerico |
| `code` | string | sì | Codice stabile machine-readable |
| `message` | string | sì | Descrizione diagnostica non usata dal frontend come identificatore |
| `path` | string | sì | Path della richiesta |
| `details` | array | sì | Errori puntuali; array vuoto quando non applicabile |

La response non contiene il campo generico `error`. Il catalogo normativo e le
regole di conversione FE/BE sono definiti in
[`15_CODICI_ERRORE_REST.md`](15_CODICI_ERRORE_REST.md).

### 1.2 Paginazione

Le collection paginate usano:

- `page`: indice zero-based, default `0`;
- `size`: default `20`, massimo `100`;
- `sort`: un solo criterio nel formato `<field>,<asc|desc>`.

Struttura comune:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0,
  "first": true,
  "last": true
}
```

Una pagina vuota è una risposta valida. Valori di paginazione, filtro o sort
non supportati producono `400 REQUEST_INVALID`.

## 2. Vehicles

### 2.1 `GET /api/v1/vehicles`

Restituisce l'elenco paginato dei veicoli.

Parametri:

| Parametro | Obbligatorio | Descrizione |
|---|---:|---|
| `query` | no | Ricerca case-insensitive per codice esterno o targa |
| `status` | no | `ACTIVE` oppure `DISABLED` |
| `page` | no | Default `0` |
| `size` | no | Default `20`, massimo `100` |
| `sort` | no | Campi ammessi: `createdAt`, `externalCode`, `plate`, `status`; default `createdAt,desc` |

Il parametro `sort` supporta un solo criterio nel formato
`<field>,<direction>`. L'applicazione aggiunge internamente un ordinamento
secondario per `id`, non configurabile dal client, per rendere deterministico
l'ordine degli elementi che hanno lo stesso valore nel campo principale.

Non sono supportati criteri multipli come:

```text
sort=createdAt,desc&sort=plate,asc
```

`200 OK`:

```json
{
  "content": [
    {
      "id": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
      "externalCode": "VAN-001",
      "plate": "FP001AA",
      "status": "ACTIVE",
      "serviceIntervalKm": 15000,
      "nextServiceAtKm": 90000,
      "createdAt": "2026-08-01T10:00:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

Errori:

- `400 REQUEST_INVALID` per parametri non validi;
- `503 SERVICE_UNAVAILABLE` se PostgreSQL non è temporaneamente disponibile;
- `500 INTERNAL_ERROR` per errori inattesi.

### 2.2 `POST /api/v1/vehicles`

Registra un nuovo veicolo. Lo stato iniziale viene assegnato dal backend a
`ACTIVE` e non è accettato nel request body.

Request `CreateVehicleRequest`:

```json
{
  "externalCode": "VAN-001",
  "plate": "FP001AA",
  "serviceIntervalKm": 15000,
  "nextServiceAtKm": 90000
}
```

Vincoli:

- `externalCode`: obbligatorio, non blank, massimo 64 caratteri;
- `plate`: obbligatoria, non blank, massimo 16 caratteri;
- `serviceIntervalKm`: intero maggiore di zero;
- `nextServiceAtKm`: intero maggiore o uguale a zero.

`201 Created`:

```http
Location: /api/v1/vehicles/97e194a8-64b3-4885-b1e6-25fd482f58c0
```

```json
{
  "id": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
  "externalCode": "VAN-001",
  "plate": "FP001AA",
  "status": "ACTIVE",
  "serviceIntervalKm": 15000,
  "nextServiceAtKm": 90000,
  "createdAt": "2026-08-01T10:00:00Z"
}
```

Errori:

- `400 REQUEST_INVALID` per Bean Validation;
- `400 REQUEST_MALFORMED_JSON` per body assente, JSON invalido o tipi non convertibili;
- `409 VEHICLE_EXTERNAL_CODE_CONFLICT` se il codice esterno è già assegnato;
- `409 VEHICLE_PLATE_CONFLICT` se la targa è già assegnata;
- `415 REQUEST_UNSUPPORTED_MEDIA_TYPE` se il media type non è supportato;
- `503 SERVICE_UNAVAILABLE` se PostgreSQL non è temporaneamente disponibile;
- `500 INTERNAL_ERROR` per errori inattesi.

Il controllo preventivo con `existsBy...` è solamente un feedback anticipato.
L'unicità autorevole è garantita dai constraint PostgreSQL
`uq_vehicles_external_code` e `uq_vehicles_plate`; le relative violazioni devono
essere convertite negli stessi errori `409` anche in presenza di richieste
concorrenti.

### 2.3 `GET /api/v1/vehicles/{vehicleId}`

`200 OK`: restituisce lo stesso `VehicleResponse` descritto per la creazione.

Errori:

- `400 REQUEST_INVALID` se `vehicleId` non è un UUID valido;
- `404 VEHICLE_NOT_FOUND` se il veicolo non esiste;
- `503 SERVICE_UNAVAILABLE` se PostgreSQL non è temporaneamente disponibile;
- `500 INTERNAL_ERROR` per errori inattesi.

### 2.4 `PATCH /api/v1/vehicles/{vehicleId}/status`

Request `ChangeVehicleStatusRequest`:

```json
{
  "status": "DISABLED"
}
```

Valori ammessi:

```text
ACTIVE
DISABLED
```

Impostare lo stato già corrente è un'operazione idempotente e restituisce
`200 OK`. La response è `VehicleResponse`.

Errori:

- `400 REQUEST_INVALID` per path o request non validi;
- `400 REQUEST_MALFORMED_JSON` per JSON o enum non convertibili;
- `404 VEHICLE_NOT_FOUND`;
- `503 SERVICE_UNAVAILABLE`;
- `500 INTERNAL_ERROR`.

## 3. Stato corrente

### `GET /api/v1/vehicles/{vehicleId}/state`

Fleet API tenta prima Redis e usa PostgreSQL come fallback. La sorgente usata è
un dettaglio interno e non modifica il contratto della response.

`200 OK`:

```json
{
  "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
  "lastSequenceNumber": 42,
  "lastSeenAt": "2026-08-01T10:15:30Z",
  "stale": false,
  "speedKmh": 72.4,
  "engineTemperatureC": 91.8,
  "batteryVoltage": 12.6,
  "odometerKm": 85312,
  "latitude": 41.9028,
  "longitude": 12.4964
}
```

Errori:

- `400 REQUEST_INVALID` se `vehicleId` non è valido;
- `404 VEHICLE_NOT_FOUND` se il veicolo non esiste;
- `404 VEHICLE_STATE_NOT_AVAILABLE` se il veicolo esiste ma non ha ancora telemetria;
- `503 SERVICE_UNAVAILABLE` soltanto quando neppure PostgreSQL consente il fallback;
- `500 INTERNAL_ERROR`.

L'indisponibilità della sola cache Redis non deve produrre `503` quando il dato è
recuperabile da PostgreSQL.

## 4. Dashboard

### `GET /api/v1/dashboard`

Restituisce la panoramica funzionale della flotta.

`200 OK`:

```json
{
  "totalVehicles": 120,
  "vehiclesByStatus": {
    "ACTIVE": 104,
    "DISABLED": 16
  },
  "recentlyReportingVehicles": 98,
  "openAlerts": 7,
  "relevantAlerts": [
    {
      "id": "f2607610-5100-4723-93d0-e6bbdcf00da0",
      "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
      "type": "ENGINE_TEMPERATURE_HIGH",
      "severity": "HIGH",
      "status": "OPEN",
      "description": "Temperatura motore oltre soglia",
      "createdAt": "2026-08-01T10:16:00Z"
    }
  ]
}
```

La finestra usata per identificare i veicoli che hanno trasmesso recentemente è
configurata dal servizio. `relevantAlerts` è limitato e ordinato per rilevanza e
`createdAt` decrescente.

Errori:

- `503 SERVICE_UNAVAILABLE` se PostgreSQL non consente la costruzione della vista;
- `500 INTERNAL_ERROR`.

## 5. Storico telemetrico

### `GET /api/v1/vehicles/{vehicleId}/telemetry`

Parametri:

| Parametro | Obbligatorio | Descrizione |
|---|---:|---|
| `from` | sì | Inizio intervallo, incluso |
| `to` | sì | Fine intervallo, incluso |
| `page` | no | Default `0` |
| `size` | no | Default `50`, massimo `100` |
| `sort` | no | `observedAt,asc` oppure `observedAt,desc`; default `observedAt,desc` |

`200 OK`:

```json
{
  "content": [
    {
      "id": 1254,
      "messageId": "dc0fc799-0913-4e72-bd2d-8ee8ccf52e22",
      "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
      "sequenceNumber": 42,
      "observedAt": "2026-08-01T10:15:30Z",
      "receivedAt": "2026-08-01T10:15:30.083Z",
      "processedAt": "2026-08-01T10:15:30.150Z",
      "speedKmh": 72.4,
      "engineTemperatureC": 91.8,
      "batteryVoltage": 12.6,
      "odometerKm": 85312,
      "latitude": 41.9028,
      "longitude": 12.4964
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 1,
  "totalPages": 1,
  "first": true,
  "last": true
}
```

Errori:

- `400 REQUEST_INVALID` per parametri, pagination o sort non validi;
- `400 REQUEST_INVALID_TIME_RANGE` quando `from` è successivo a `to`;
- `404 VEHICLE_NOT_FOUND`;
- `503 SERVICE_UNAVAILABLE`;
- `500 INTERNAL_ERROR`.

## 6. Alert

### 6.1 `GET /api/v1/vehicles/{vehicleId}/alerts`

Filtri opzionali:

- `status`: `OPEN`, `ACKNOWLEDGED`, `CLOSED`;
- `type`;
- `severity`;
- `from` e `to` su `createdAt`;
- `page`, `size`, `sort`.

Il sort predefinito è `createdAt,desc`. La response usa la struttura paginata e
contiene `MaintenanceAlertResponse`.

### 6.2 `GET /api/v1/alerts`

Filtri opzionali:

- `vehicleId`;
- `status`;
- `type`;
- `severity`;
- `from`;
- `to`;
- `page`;
- `size`;
- `sort`.

`MaintenanceAlertResponse`:

```json
{
  "id": "f2607610-5100-4723-93d0-e6bbdcf00da0",
  "vehicleId": "97e194a8-64b3-4885-b1e6-25fd482f58c0",
  "sourceMessageId": "dc0fc799-0913-4e72-bd2d-8ee8ccf52e22",
  "type": "ENGINE_TEMPERATURE_HIGH",
  "severity": "HIGH",
  "description": "Temperatura motore oltre soglia",
  "status": "OPEN",
  "createdAt": "2026-08-01T10:16:00Z",
  "acknowledgedAt": null,
  "closedAt": null
}
```

Errori delle collection alert:

- `400 REQUEST_INVALID` per filtri, UUID, pagination o sort non validi;
- `400 REQUEST_INVALID_TIME_RANGE`;
- `404 VEHICLE_NOT_FOUND` solo per la collection scoped al veicolo;
- `503 SERVICE_UNAVAILABLE`;
- `500 INTERNAL_ERROR`.

### 6.3 `GET /api/v1/alerts/{alertId}`

`200 OK`: restituisce `MaintenanceAlertResponse`.

Errori:

- `400 REQUEST_INVALID` se `alertId` non è valido;
- `404 ALERT_NOT_FOUND`;
- `503 SERVICE_UNAVAILABLE`;
- `500 INTERNAL_ERROR`.

### 6.4 `PATCH /api/v1/alerts/{alertId}`

Request `ChangeAlertStatusRequest`:

```json
{
  "status": "ACKNOWLEDGED"
}
```

Target ammessi:

```text
ACKNOWLEDGED
CLOSED
```

Transizioni supportate:

```text
OPEN -> ACKNOWLEDGED
OPEN -> CLOSED
ACKNOWLEDGED -> CLOSED
```

Impostare lo stato già corrente è idempotente. Una transizione inversa o non
supportata produce conflitto.

`200 OK`: restituisce `MaintenanceAlertResponse` aggiornato.

Errori:

- `400 REQUEST_INVALID`;
- `400 REQUEST_MALFORMED_JSON`;
- `404 ALERT_NOT_FOUND`;
- `409 ALERT_STATUS_TRANSITION_CONFLICT`;
- `503 SERVICE_UNAVAILABLE`;
- `500 INTERNAL_ERROR`.

## 7. Operations

Endpoint esposti:

```text
/actuator/health
/actuator/info
/actuator/prometheus
```

Gli endpoint operativi non fanno parte del base path `/api/v1` e non devono
esporre informazioni sensibili nell'ambiente locale.

## 8. Mappatura sintetica degli errori

| Scenario | HTTP | Codice |
|---|---:|---|
| Bean Validation o query/path non validi | 400 | `REQUEST_INVALID` |
| JSON/body/enum non decodificabile | 400 | `REQUEST_MALFORMED_JSON` |
| Intervallo temporale invalido | 400 | `REQUEST_INVALID_TIME_RANGE` |
| Metodo HTTP non supportato | 405 | `REQUEST_METHOD_NOT_ALLOWED` |
| Media type non supportato | 415 | `REQUEST_UNSUPPORTED_MEDIA_TYPE` |
| Veicolo assente | 404 | `VEHICLE_NOT_FOUND` |
| Stato corrente non ancora disponibile | 404 | `VEHICLE_STATE_NOT_AVAILABLE` |
| Codice esterno duplicato | 409 | `VEHICLE_EXTERNAL_CODE_CONFLICT` |
| Targa duplicata | 409 | `VEHICLE_PLATE_CONFLICT` |
| Alert assente | 404 | `ALERT_NOT_FOUND` |
| Transizione alert non supportata | 409 | `ALERT_STATUS_TRANSITION_CONFLICT` |
| Dipendenza temporaneamente indisponibile | 503 | `SERVICE_UNAVAILABLE` |
| Errore inatteso | 500 | `INTERNAL_ERROR` |

## 9. OpenAPI

Fleet API deve pubblicare:

```text
/v3/api-docs
/swagger-ui/index.html
```

Il documento OpenAPI deve descrivere request, response, header `Location`,
paginazione, enum e tutte le error response definite in questa specifica.
L'OpenAPI generato deve essere verificato tramite test di contratto per evitare
divergenze tra documentazione e implementazione.
