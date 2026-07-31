# Specifica API

## 1. Convenzioni

Base path:

```text
/api/v1
```

Formato:

```text
application/json
```

Timestamp:

```text
ISO-8601 UTC
```

Error response:

```json
{
  "timestamp": "2026-08-01T10:20:00Z",
  "status": 400,
  "code": "INVALID_REQUEST",
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

## 2. Vehicles

### `GET /api/v1/vehicles`

Parametri:

- `query`: ricerca per codice esterno o targa;
- `status`: filtro per stato operativo;
- `page`;
- `size`;
- `sort`.

La risposta è paginata.

### `POST /api/v1/vehicles`

```json
{
  "externalCode": "VAN-001",
  "plate": "FP001AA",
  "status": "ACTIVE",
  "serviceIntervalKm": 15000,
  "nextServiceAtKm": 90000
}
```

Risposte:

- `201 Created`
- `400 Bad Request`
- `409 Conflict`

### `GET /api/v1/vehicles/{vehicleId}`

### `PATCH /api/v1/vehicles/{vehicleId}/status`

```json
{
  "status": "DISABLED"
}
```

## 3. Stato corrente

### `GET /api/v1/vehicles/{vehicleId}/state`

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

## 4. Dashboard

### `GET /api/v1/dashboard`

Restituisce la panoramica funzionale della flotta:

```json
{
  "totalVehicles": 120,
  "vehiclesByStatus": {
    "ACTIVE": 104,
    "DISABLED": 16
  },
  "recentlyReportingVehicles": 98,
  "openAlerts": 7,
  "relevantAlerts": []
}
```

La finestra usata per determinare i veicoli che hanno trasmesso recentemente è
configurata dal servizio.

## 5. Storico

### `GET /api/v1/vehicles/{vehicleId}/telemetry`

Parametri:

- `from`
- `to`
- `page`
- `size`
- `sort`

Response paginata:

```json
{
  "content": [],
  "page": 0,
  "size": 50,
  "totalElements": 0,
  "totalPages": 0
}
```

## 6. Alert

### `GET /api/v1/vehicles/{vehicleId}/alerts`

Filtri:

- `status`
- `type`
- `severity`
- `from`
- `to`
- `page`
- `size`
- `sort`

La risposta è paginata.

### `GET /api/v1/alerts`

Filtri:

- `vehicleId`;
- `status`;
- `type`;
- `severity`;
- `from`;
- `to`;
- `page`;
- `size`;
- `sort`.

### `GET /api/v1/alerts/{alertId}`

Restituisce il dettaglio di un alert oppure `404 Not Found`.

### `PATCH /api/v1/alerts/{alertId}`

```json
{
  "status": "ACKNOWLEDGED"
}
```

Transizioni invalide: `409 Conflict`.

## 7. Operations

```text
/actuator/health
/actuator/prometheus
```

## 8. Pagination

- page size con limite massimo;
- ordering deterministico;
- empty page valida;
- intervallo temporale non valido: `400 Bad Request`.

## 9. OpenAPI

L'applicazione pubblica un documento OpenAPI coerente con questa specifica.
