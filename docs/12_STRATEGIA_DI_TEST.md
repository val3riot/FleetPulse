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

- validazione di esistenza e stato dei veicoli nel processor;
- classificazione `UNKNOWN_VEHICLE` e `VEHICLE_DISABLED`;
- assenza di side effect per la telemetria rifiutata;
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
- rejection publication;
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

#### Matrice FP-017

| Requisito | Livello | Evidenza |
|---|---|---|
| Socket reali e connessione persistente | Integration | `TcpServerIntegrationTest` |
| Header e payload frammentati | Integration | invio byte-per-byte su socket loopback |
| Frame concatenati | Integration | più frame in una singola socket write, verificati in ordine |
| Frame completo seguito da frame parziale | Integration | consegna del primo frame e cleanup sul secondo incompleto |
| Client lento e read timeout | Integration | scadenza su client idle e frame parziale |
| Disconnect a metà frame | Integration | EOF durante payload e rilascio delle risorse |
| Limite connessioni | Integration | saturazione concorrente, rifiuto e riuso del permit |
| Graceful shutdown | Integration | completamento in-flight e chiusura forzata dopo il grace period |
| Simulator su TCP reale | Smoke integration | provisioning Spring e frame compatibile inviato su loopback |
| ACK/NACK applicativo | Contract/Integration | contratto JSON e decoder coperti; emissione gateway rinviata al `FrameHandler` Kafka |

I test TCP usano porte effimere, risorse racchiuse in `try-with-resources` e
attese con deadline. Non richiedono Docker né porte locali prestabilite. L'ACK
end-to-end non può essere simulato come accettazione definitiva: per contratto
`ACCEPTED` richiede la conferma di pubblicazione Kafka, responsabilità del
`FrameHandler` di produzione non ancora implementato da FP-021.

### Vehicle Simulator

- binding e validazione di tutte le proprietà;
- provisioning idempotente e recovery dal conflitto `409`;
- stato e sequence number isolati per veicolo;
- framing compatibile con il codec condiviso;
- connessione persistente e chiusura concorrente durante una write;
- progressione, cap, jitter disabilitabile e limite dei reconnect;
- workload su virtual thread e arresto tramite lifecycle Spring;
- smoke test con contesto Spring, Fleet API simulata e socket TCP reale.

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

### E2E-006 — Rifiuto asincrono del veicolo

1. invia telemetria per un veicolo sconosciuto o disabilitato;
2. verifica che il gateway restituisca `ACCEPTED` dopo la pubblicazione Kafka;
3. verifica il rejection event su `telemetry.rejected.v1`;
4. verifica l'assenza di sample, aggiornamenti Redis e alert.

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

## ADR di riferimento

- [ADR-002 — Protocollo TCP length-prefixed](adr/ADR-002-PROTOCOLLO-TCP-LENGTH-PREFIXED.md)
- [ADR-004 — PostgreSQL come source of truth](adr/ADR-004-POSTGRESQL-SOURCE-OF-TRUTH.md)
- [ADR-005 — Redis come cache ricostruibile](adr/ADR-005-REDIS-CACHE-RICOSTRUIBILE.md)
- [ADR-006 — At-least-once con application idempotency](adr/ADR-006-AT-LEAST-ONCE-E-IDEMPOTENCY.md)
- [ADR-007 — Validazione del veicolo nel telemetry processor](adr/ADR-007-VALIDAZIONE-VEICOLO.md)
- [ADR-008 — Significato di lastSeenAt e freshness dello stato](adr/ADR-008-LAST-SEEN-AT-E-FRESHNESS.md)

## State API — verifiche FP-031

| Requisito | Evidenza nel modulo fleet-api |
|---|---|
| Cache hit senza query PostgreSQL | `VehicleStateServiceTest.cacheHitAvoidsSampleQueryAndRepair`, test REST hit dopo repair |
| Cache miss, failure e repair isolato | `VehicleStateServiceTest`, `VehicleStateApiIntegrationTest` |
| Freshness e confine esatto con clock fisso | `VehicleStateServiceTest.freshnessHasExactBoundaryAndDoesNotCorrectFutureTime` |
| 404 distinti, UUID invalido, veicolo disabilitato consultabile | `VehicleStateApiIntegrationTest` |
| Redis arrestato, poi entrambi i servizi arrestati | `VehicleStateUnavailableIntegrationTest` |
| PostgreSQL arrestato: hit stale servibile, miss con 503 | `VehicleStateCacheHitDatabaseUnavailableIntegrationTest` |
| Chiave orfana fresh/stale servibile, poi 404 alla scadenza reale | `VehicleStateApiIntegrationTest.missingStateAndMissingVehicleAreDifferentAfterCacheExpires` |
| Ordering stabile e isolamento veicolo PostgreSQL | `VehicleStateApiIntegrationTest.latestSampleOrdersByObservationThenSequenceThenStableId`, `fallbackDoesNotReadAnotherVehiclesNewerSample` |
| Migration V2 da database vuoto | `VehicleStateApiIntegrationTest.latestStateIndexIsCreatedByMigration` |
| JSON, numeri e timestamp compatibili con FP-030 | `RedisLatestStateCodecTest` |
| TTL reale, concorrenza e precisione della sequenza | `RedisLatestStateProjectionIntegrationTest` |
| Repair concorrente con stato più recente | `VehicleStateApiIntegrationTest.repairDoesNotOverwriteNewerConcurrentProjection` |
| Timeout Redis tradotto per il fallback | `RedisLatestStateTimeoutTest` |
| Configurazione invalida rifiutata | `VehicleStatePropertiesTest`, `LatestStateProjectionPropertiesTest` |
| Metriche senza tag dinamici, warning limitati e senza payload | `VehicleStateObservabilityTest` |
| Endpoint presente nella specifica generata | `OpenApiIntegrationTest` |

Il percorso REST usa PostgreSQL e Redis Testcontainers reali. Il guasto dei servizi
arresta container isolati, senza modificare l'infrastruttura di sviluppo.
La riconciliazione degli hit e gli ulteriori scenari di recovery restano fuori
FP-031; si vedano ADR-010 e la ticket di resilienza FP-039.

Verifica finale FP-031 del 2026-09-08: `clean verify` dalla root con Docker
rootless, BUILD SUCCESS; 496 test, zero failure/errori/skipped. Fleet API:
189 test, di cui 78 nel package dello stato. Superati anche Compose config e
`git diff --check`. Il percorso finale è Redis prima di PostgreSQL, inclusi
hit durante guasto DB e cache orfana fresh/stale fino alla scadenza.
