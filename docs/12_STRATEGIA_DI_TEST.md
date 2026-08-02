# Strategia di test

## 1. Obiettivi

La test suite verifica:

- correttezza del protocollo;
- invarianti del dominio;
- integrazione infrastrutturale;
- idempotency;
- recovery;
- cache fallback;
- comportamento osservabile sotto guasto.

## 2. Unit test

### TCP codec

- encode/decode;
- header diviso;
- payload diviso;
- più frame nello stesso buffer;
- frame completo più frame parziale;
- lunghezza eccessiva;
- EOF incompleto;
- protocol version non supportata;
- JSON malformato.

### Domain

- validazione dei veicoli;
- sanity range;
- soglie alert;
- transizioni degli alert;
- projection dello stato.

### Retry classification

- errori retryable;
- errori permanenti;
- tentativi limitati.

## 3. Integration test

Testcontainers fornisce istanze reali di:

- PostgreSQL;
- Kafka;
- Redis;
- Toxiproxy.

### PostgreSQL

- migration Flyway;
- unique `messageId`;
- unique alert source/type;
- ordering e pagination;
- transaction rollback.

### Fleet API REST

- avvio del contesto Spring;
- creazione valida con `201 Created` e header `Location`;
- response `VehicleResponse`;
- validazione di ogni campo di `CreateVehicleRequest`;
- body mancante e JSON malformato;
- enum e tipi non convertibili;
- conflitto su codice esterno e targa;
- conversione delle violazioni reali dei constraint PostgreSQL in `409`;
- richieste concorrenti con una sola creazione valida;
- `404` per risorse assenti;
- `503 SERVICE_UNAVAILABLE` per database indisponibile;
- serializzazione uniforme di `ApiErrorResponse`;
- `details` tipizzati per Bean Validation;
- coerenza tra OpenAPI e controller.

### Kafka

- pubblicazione;
- record key;
- consumer delivery;
- duplicate processing;
- dead-letter publication.

### Redis

- write/read;
- TTL;
- cache miss;
- fallback PostgreSQL;
- indisponibilità.

### TCP

- server e client reali;
- client concorrenti;
- timeout;
- disconnect a metà frame;
- ACK.

## 4. End-to-end

### E2E-001 — Flusso nominale

1. registra veicolo;
2. invia telemetria;
3. verifica ACK;
4. verifica persistenza;
5. verifica stato corrente.

### E2E-002 — Alert

1. invia valore oltre soglia;
2. verifica un alert;
3. ripeti lo stesso `messageId`;
4. verifica assenza di duplicato.

### E2E-003 — Restart del processor

1. arresta il processor;
2. invia eventi;
3. riavvia;
4. verifica elaborazione eventuale una sola volta.

### E2E-004 — Redis non disponibile

1. persisti telemetria;
2. rendi Redis indisponibile;
3. interroga lo stato;
4. verifica fallback;
5. verifica metrica.

### E2E-005 — Input TCP invalido

1. invia frame malformato;
2. verifica rifiuto;
3. verifica gateway ancora disponibile.

## 5. Failure injection

Toxiproxy può introdurre:

- latency;
- connection reset;
- timeout;
- bandwidth restriction.

## 6. Carico di riferimento

```text
vehicles: 50
message interval: 2 secondi
duration: 5 minuti
duplicate probability: 2%
disconnect probability: 1%
```

## 7. Quality gate

- unit test verdi;
- integration test verdi;
- end-to-end documentati;
- nessun retry infinito;
- nessun secret;
- avvio da database vuoto;
- contratto REST ed errori coperti da test;
- OpenAPI coerente con l'implementazione;
- shutdown e restart ripetibili;
- idempotency verificata.
