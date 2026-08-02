# Codici di errore REST

## 1. Scopo

Questo documento definisce i codici di errore pubblici restituiti da Fleet API.
I codici costituiscono il contratto stabile usato dai client, incluso Fleet
Dashboard, e sono distinti dai codici del protocollo TCP definiti in
[`06_PROTOCOLLO_TCP.md`](06_PROTOCOLLO_TCP.md).

Il frontend deve basare la conversione degli errori sul campo `code`, non sul
testo di `message` né sul solo HTTP status.

## 2. Formato della response

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

`details` è sempre presente:

- contiene uno o più elementi per gli errori puntuali di validazione;
- è un array vuoto per conflitti, not found, errori infrastrutturali e interni;
- non è una `Map<String, Object>`;
- non espone stack trace, nomi di classi Java, query SQL o credenziali.

Dettaglio tipizzato:

```text
ValidationErrorDetail(field: String, message: String)
```

Il campo `message` dell'errore principale è diagnostico e può cambiare o essere
localizzato. Il campo `code` è invece stabile.

## 3. Nomenclatura

I codici seguono il formato:

```text
<AREA>_<CONDIZIONE>
```

- `AREA` identifica la risorsa o l'ambito dell'errore;
- `CONDIZIONE` descrive la causa in modo stabile e indipendente dal messaggio;
- i termini sono in inglese, maiuscoli e separati da underscore;
- un codice esistente non cambia significato e non viene riutilizzato;
- la stessa condizione deve produrre lo stesso codice indipendentemente dal
  punto in cui viene intercettata.

## 4. Catalogo

### 4.1 Richiesta

| Codice | HTTP status | Significato |
|---|---:|---|
| `REQUEST_INVALID` | `400 Bad Request` | Uno o più campi, path variable, query parameter, valori di paginazione o sort non rispettano i vincoli |
| `REQUEST_MALFORMED_JSON` | `400 Bad Request` | Body assente quando obbligatorio, JSON non decodificabile, enum sconosciuto o tipo non convertibile |
| `REQUEST_INVALID_TIME_RANGE` | `400 Bad Request` | `from` è successivo a `to` o l'intervallo non rispetta i limiti documentati |
| `REQUEST_METHOD_NOT_ALLOWED` | `405 Method Not Allowed` | Il metodo HTTP non è supportato per la risorsa |
| `REQUEST_UNSUPPORTED_MEDIA_TYPE` | `415 Unsupported Media Type` | Il `Content-Type` della richiesta non è supportato |

### 4.2 Veicoli

| Codice | HTTP status | Significato |
|---|---:|---|
| `VEHICLE_NOT_FOUND` | `404 Not Found` | Il veicolo richiesto non esiste |
| `VEHICLE_STATE_NOT_AVAILABLE` | `404 Not Found` | Il veicolo esiste ma non ha ancora uno stato telemetrico disponibile |
| `VEHICLE_PLATE_CONFLICT` | `409 Conflict` | La targa è già assegnata |
| `VEHICLE_EXTERNAL_CODE_CONFLICT` | `409 Conflict` | Il codice esterno è già assegnato |

### 4.3 Alert

| Codice | HTTP status | Significato |
|---|---:|---|
| `ALERT_NOT_FOUND` | `404 Not Found` | L'alert richiesto non esiste |
| `ALERT_STATUS_TRANSITION_CONFLICT` | `409 Conflict` | La transizione di stato richiesta non è consentita |

### 4.4 Infrastruttura e errori inattesi

| Codice | HTTP status | Significato |
|---|---:|---|
| `SERVICE_UNAVAILABLE` | `503 Service Unavailable` | Una dipendenza necessaria è temporaneamente indisponibile e non esiste un fallback valido |
| `INTERNAL_ERROR` | `500 Internal Server Error` | Si è verificato un errore inatteso |

## 5. Regole di conversione backend

### 5.1 Bean Validation e binding

| Origine Spring | Codice |
|---|---|
| `MethodArgumentNotValidException` | `REQUEST_INVALID` con un dettaglio per ogni campo |
| `ConstraintViolationException` | `REQUEST_INVALID` |
| `MethodArgumentTypeMismatchException` | `REQUEST_INVALID` |
| `MissingServletRequestParameterException` | `REQUEST_INVALID` |
| `MissingPathVariableException` | `REQUEST_INVALID` |
| `HttpMessageNotReadableException` | `REQUEST_MALFORMED_JSON` |
| `HttpRequestMethodNotSupportedException` | `REQUEST_METHOD_NOT_ALLOWED` |
| `HttpMediaTypeNotSupportedException` | `REQUEST_UNSUPPORTED_MEDIA_TYPE` |

Un enum non riconosciuto nel JSON, un numero non convertibile o un body
obbligatorio assente ricadono in `REQUEST_MALFORMED_JSON`.

### 5.2 Unicità dei veicoli

I controlli `existsByExternalCode` e `existsByPlate` sono opzionali e servono
soltanto a restituire rapidamente un feedback. Non sostituiscono i constraint
del database.

La conversione autorevole deve riconoscere le violazioni di:

| Constraint PostgreSQL | Codice REST |
|---|---|
| `uq_vehicles_external_code` | `VEHICLE_EXTERNAL_CODE_CONFLICT` |
| `uq_vehicles_plate` | `VEHICLE_PLATE_CONFLICT` |

Due richieste concorrenti che superano contemporaneamente il controllo
preventivo devono comunque produrre una sola creazione valida e un `409`
coerente per l'altra richiesta.

Il codice non deve dipendere dal testo localizzato dell'errore PostgreSQL. Deve
preferire il nome del constraint disponibile nella causa dell'eccezione.

### 5.3 Dipendenze temporaneamente indisponibili

Errori di connessione, timeout o indisponibilità temporanea di PostgreSQL che
impediscono di completare la richiesta sono convertiti in
`503 SERVICE_UNAVAILABLE` e registrati con log diagnostico.

L'indisponibilità di Redis non produce automaticamente `503`: per lo stato
corrente Fleet API deve tentare il fallback PostgreSQL. `503` è corretto solo
se non è possibile costruire una risposta valida.

### 5.4 Catch-all

Le eccezioni non previste sono convertite in `500 INTERNAL_ERROR`.

Il backend deve:

- restituire al client un messaggio generico;
- registrare l'eccezione completa nei log;
- non esporre stack trace o dettagli interni nella response.

## 6. Regole per il frontend

- i codici sono identificatori machine-readable e non testi da mostrare;
- il frontend associa ogni codice a un messaggio localizzato;
- un codice sconosciuto usa un messaggio generico basato sulla famiglia dello
  status HTTP;
- `details` viene usato per associare gli errori ai campi del form quando il
  codice è `REQUEST_INVALID`;
- `REQUEST_MALFORMED_JSON` viene mostrato come errore generale della richiesta;
- i conflitti di targa e codice esterno possono essere associati ai rispettivi
  campi;
- il frontend può proporre un retry soltanto per `SERVICE_UNAVAILABLE`, errori
  di rete senza response o altri casi esplicitamente dichiarati retryable;
- `INTERNAL_ERROR` non deve essere ritentato automaticamente in loop.

## 7. Evoluzione

Ogni nuovo errore REST deve essere aggiunto:

1. a questo catalogo;
2. all'enum backend;
3. al `GlobalExceptionHandler` o al punto di conversione pertinente;
4. alla specifica OpenAPI;
5. ai test di serializzazione e integrazione.

La rimozione o la rinomina di un codice richiede un piano di compatibilità con i
client esistenti.
