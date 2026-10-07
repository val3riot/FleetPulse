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

La specifica OpenAPI pubblicata descrive gli endpoint operativi di veicoli,
stato corrente, dashboard, storico telemetrico e alert, incluse le transizioni
di stato degli alert. I contratti delle sezioni seguenti sono implementati da
FP-004–FP-008 e FP-031–FP-037. FP-038 completa la verifica dei contratti delle
API di lettura; non introduce nuovi endpoint.

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
- `size`: minimo `1`, massimo `100`; default `20` per veicoli e `50` per
  storico telemetrico e collection alert;
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

I default specifici di ciascun endpoint prevalgono sulle convenzioni comuni.
Tutti i sette campi della pagina sono presenti: `content` è un array,
`page`, `size` e `totalPages` sono interi a 32 bit, `totalElements` è un intero
a 64 bit, `first` e `last` sono booleani. `totalElements` conta tutti i risultati
che soddisfano i filtri, non soltanto gli elementi della pagina.

Una pagina vuota è una risposta valida. Anche una pagina oltre l'ultima
restituisce `200`, con `content: []`, `page` e `size` richiesti e i totali
relativi alla ricerca. `first` indica `page == 0`; `last` indica l'assenza di
una pagina successiva. Senza risultati, `totalElements` e `totalPages` sono
zero. Il veicolo assente sulle collection scoped resta un `404 VEHICLE_NOT_FOUND`.
Valori di paginazione, filtro o sort non supportati producono
`400 REQUEST_INVALID`.

Il tie-breaker rende l'ordinamento deterministico a dati invariati. Richieste
di pagine diverse possono osservare modifiche concorrenti: non è garantito
uno snapshot unico fra richieste.

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

`lastSeenAt` è l'`observedAt` della rilevazione selezionata come stato corrente:
indica il momento della misura in UTC. Il mapping è identico per cache hit e
fallback PostgreSQL. `stale` esprime la freshness della misura a partire da
questo timestamp, non dalla scadenza Redis o dal momento di elaborazione.
La soglia configurabile `fleetpulse.api.state.stale-after` ha default `1m`:
`stale` è vero se l'età della misura supera strettamente la soglia. Alla soglia
esatta e per timestamp futuri è falso. Si vedano
[ADR-008](adr/ADR-008-LAST-SEEN-AT-E-FRESHNESS.md) e
[ADR-010](adr/ADR-010-STATE-API-FALLBACK.md).

Un hit Redis valido non interroga PostgreSQL ed è servibile anche durante un
suo guasto. La verifica dell'esistenza del veicolo avviene solo nel fallback.
Per il progetto di studio si accetta una chiave temporaneamente orfana dopo
cancellazione DB: senza altre scritture scompare entro il TTL residuo (default
`5m`). `stale` non segnala cancellazioni e non impone un'età massima alla risposta.
Compromesso e limiti sono descritti in ADR-010. I veicoli disabilitati restano consultabili.
Miss, errore di connessione, timeout o JSON invalido attivano il fallback;
il successivo ripopolamento è best effort e non fa fallire la risposta.

La selezione del latest sample e la ricostruzione seguono il criterio
`observedAt`, poi `sequenceNumber` (entrambi discendenti), definito in
[ADR-009 — Contratto e aggiornamento della latest-state projection](adr/ADR-009-LATEST-STATE-PROJECTION.md). Il fallback aggiunge `id DESC` come tie-breaker stabile. Gli ADR esplicitano anche i limiti in caso di parità completa e perdita della cache.

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
- `404 VEHICLE_NOT_FOUND` se, durante il fallback, il veicolo non esiste;
- `404 VEHICLE_STATE_NOT_AVAILABLE` se il fallback trova il veicolo ma nessuna telemetria;
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

L'endpoint è implementato da FP-037 e non accetta filtri o parametri di paginazione.
Un database vuoto restituisce `200`, conteggi zero, entrambe le chiavi `ACTIVE`
e `DISABLED` e `relevantAlerts: []`. Tutti i conteggi sono interi a 64 bit.

- `totalVehicles` include tutti i veicoli ed è la somma di `vehiclesByStatus`.
- `recentlyReportingVehicles` conta una sola volta ogni veicolo con almeno un
  sample persistito il cui `observedAt` è nell'intervallo inclusivo
  `[now - reportingWindow, now]`, inclusi i veicoli `DISABLED`. `now` viene letto
  una volta dal `Clock` UTC. I timestamp futuri sono esclusi da questo KPI;
  questo filtro non modifica la semantica del flag `stale` della State API,
  che continua a seguire ADR-008/010. La finestra ha default `1m`, minimo `1ms`
  e massimo `1d`.
- `openAlerts` conta soltanto `OPEN`, escludendo `ACKNOWLEDGED` e `CLOSED`.
- `relevantAlerts` include `OPEN` e `ACKNOWLEDGED`, esclude `CLOSED` e usa
  severità `CRITICAL > HIGH > MEDIUM > LOW`, poi `createdAt DESC`, infine
  `id ASC`. Il limite configurabile ha default `10` e intervallo `1–100`.
  Ogni elemento espone solo i sette campi dell'esempio, senza campi del dettaglio.

La vista usa quattro SELECT aggregate/projection tramite i repository Spring Data
JPA di veicoli, telemetria e alert, in una transazione
read-only `REPEATABLE READ`: conteggi e lista vedono lo stesso snapshot anche se
un aggiornamento concorrente viene committato tra due query. Non carica entity
per singolo veicolo e non accede a Redis. Le projection tipizzate sono separate
dai DTO HTTP; la lista rilevante usa `List` con `Pageable`, senza il conteggio
aggiuntivo di una `Page`. La migration V5 aggiunge l'indice
`(observed_at, vehicle_id)` per il filtro temporale globale. Una failure di una
query impedisce la risposta completa; non vengono restituiti zeri sostitutivi
né viste parziali.

Configurazione: `fleetpulse.api.dashboard.reporting-window`
(`API_DASHBOARD_REPORTING_WINDOW`) e
`fleetpulse.api.dashboard.relevant-alerts-limit`
(`API_DASHBOARD_RELEVANT_ALERTS_LIMIT`), validate all'avvio.

Errori:

- `503 SERVICE_UNAVAILABLE` se PostgreSQL non consente la costruzione della vista;
- `500 INTERNAL_ERROR`.

### Parametri scalari delle query history e alert

Per lo storico e le collection alert, ogni parametro dichiarato può comparire
una sola volta. Parametri ripetuti, anche con valori identici, oppure presenti
con valore vuoto o composto soltanto da spazi producono
`400 REQUEST_INVALID`. I filtri opzionali non desiderati si omettono;
l'omissione dei parametri di paginazione applica i default dell'endpoint.
La regola si applica a from/to/page/size/sort dello storico e a
vehicleId/status/type/severity/from/to/page/size/sort delle collection alert
(vehicleId è un filtro query della sola collection globale).

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

L'intervallo si applica a `observedAt` in UTC; `from == to` è valido e seleziona
le misure esattamente a quell'istante. Entrambi i limiti sono obbligatori.
L'ordinamento aggiunge `id` nella stessa direzione di `observedAt` come
tie-breaker deterministico. `sequenceNumber` non è un criterio di ordinamento
dello storico; appartiene alla selezione del latest-state descritta nella sezione 3.

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

I valori ammessi per `type` sono:

- `ENGINE_TEMPERATURE_HIGH`;
- `BATTERY_VOLTAGE_LOW`;
- `SERVICE_DUE`.

I valori ammessi per `severity`, in ordine crescente, sono:

- `LOW`;
- `MEDIUM`;
- `HIGH`;
- `CRITICAL`.

Valori differenti nei filtri producono `400 REQUEST_INVALID`; valori differenti
in un request body producono `400 REQUEST_MALFORMED_JSON`.

### 6.1 `GET /api/v1/vehicles/{vehicleId}/alerts`

Filtri opzionali:

- `status`: `OPEN`, `ACKNOWLEDGED`, `CLOSED`;
- `type`;
- `severity`;
- `from` e `to` su `createdAt`;
- `page`, `size`, `sort`.

Le collection alert usano `page=0`, `size=50`, minimo `size=1` e massimo
`size=100`. I filtri valorizzati si combinano con AND; la collection scoped
aggiunge sempre il vincolo del veicolo indicato nel path.

`from` e `to` sono limiti UTC inclusivi su `createdAt`. Se è presente soltanto
`from`, non si applica un limite superiore; se è presente soltanto `to`, non
si applica un limite inferiore. Se entrambi sono assenti non si filtra per
intervallo temporale. `from == to` è valido; `from > to` produce
`400 REQUEST_INVALID_TIME_RANGE`.

Il sort predefinito è `createdAt,desc`; sono ammessi `createdAt,asc` e
`createdAt,desc`. L'ordinamento aggiunge sempre `id` nella stessa direzione come
tie-breaker deterministico. La response usa la struttura paginata e contiene
`MaintenanceAlertResponse`.

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

Default, limiti di paginazione, semantica AND, intervallo temporale, sort e
tie-breaker sono gli stessi della collection scoped al veicolo. Un filtro
`vehicleId` valido senza corrispondenze restituisce una pagina vuota; non
richiede la verifica di esistenza prevista per il path scoped.

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

Gli identificativi sono stringhe UUID e gli istanti sono timestamp UTC
(formato OpenAPI `date-time`). `acknowledgedAt` e `closedAt` possono essere
null: OPEN non ha timestamp di transizione; ACKNOWLEDGED ha acknowledgedAt;
CLOSED ha closedAt e conserva acknowledgedAt se la chiusura segue un acknowledge.
La nullabilità di questi timestamp va distinta dall'obbligatorietà degli
altri campi e verificata nel contratto OpenAPI da FP-038.

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
supportata produce conflitto. Concorrenza, rivalutazione bounded e controllo
della versione sono definiti in
[ADR-011 — Transizioni alert con optimistic locking](adr/ADR-011-ALERT-OPTIMISTIC-LOCKING.md).

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

## ADR di riferimento

- [ADR-001 — Confini dei servizi](adr/ADR-001-CONFINI-DEI-SERVIZI.md)
- [ADR-004 — PostgreSQL come source of truth](adr/ADR-004-POSTGRESQL-SOURCE-OF-TRUTH.md)
- [ADR-005 — Redis come cache ricostruibile](adr/ADR-005-REDIS-CACHE-RICOSTRUIBILE.md)
- [ADR-007 — Validazione del veicolo nel telemetry processor](adr/ADR-007-VALIDAZIONE-VEICOLO.md)

- [ADR-009 — Contratto e aggiornamento della latest-state projection](adr/ADR-009-LATEST-STATE-PROJECTION.md)
- [ADR-011 — Transizioni alert con optimistic locking](adr/ADR-011-ALERT-OPTIMISTIC-LOCKING.md)
