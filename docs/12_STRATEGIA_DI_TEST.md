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

Per le regole alert, la boundary value analysis copre il valore immediatamente
inferiore, uguale e immediatamente superiore a ogni soglia. Sono inoltre
verificati assenza e molteplicità degli alert, ordine e output deterministici,
invarianti degli input e rifiuto della configurazione non valida all'avvio.

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

## Regole alert — verifiche FP-033

| Requisito | Evidenza nel modulo telemetry-processor |
|---|---|
| Boundary di temperatura, batteria e manutenzione | `AlertRuleTest` |
| Zero, uno o più alert e ordine deterministico | `AlertEvaluatorTest` |
| Invarianti degli input e descrizioni bounded | `AlertDomainInvariantTest` |
| Properties valide e startup impedito per valori invalidi | `AlertThresholdPropertiesTest` |
| Mapping dal contratto di telemetria | `AlertTelemetryMapperTest` |
| Lettura di `nextServiceAtKm` da PostgreSQL | `PostgreSqlVehicleRegistryIntegrationTest` |
| V3 su database vuoto e upgrade da V1 con dati | `AlertEnumMigrationIntegrationTest` |
| Enforcement PostgreSQL di type e severity | `AlertEnumMigrationIntegrationTest` |

Verifica finale FP-033 del 2026-09-13: `./mvnw verify` dalla root con Docker,
BUILD SUCCESS; 561 test, zero failure/errori/skipped. La persistenza degli alert
e il transaction boundary con il sample appartengono a FP-034.

## Persistenza alert transazionale — verifiche FP-034

| Requisito | Evidenza nel modulo telemetry-processor |
|---|---|
| Mapping completo candidate → entity `OPEN` con timestamp deterministico | `MaintenanceAlertMapperTest` |
| Sample senza regole e aggregate con uno o più alert distinti | `TelemetryAggregateWriterIntegrationTest`, `TelemetrySamplePersistenceIntegrationTest` |
| Sample e alert visibili prima dell'aggiornamento Redis e fuori da una transaction attiva | `TelemetrySamplePersistenceIntegrationTest.persistsDerivedAlertBeforeUpdatingRedis` |
| Rollback del sample quando fallisce l'insert dell'alert | `TelemetryAggregateWriterIntegrationTest.rollsBackSampleWhenAlertViolatesCompositeForeignKey` |
| Foreign key composita e unique source/type applicate da PostgreSQL | `TelemetryAggregateWriterIntegrationTest` |
| Replay, restart e consegne concorrenti producono un solo aggregate | `TelemetrySamplePersistenceIntegrationTest` |
| Riconoscimento dei soli constraint idempotenti previsti | `TelemetryPersistenceFailureClassifierTest`, `TelemetryEventProcessingServiceTest` |

Verifica finale FP-034 del 2026-09-13: `./mvnw verify` dalla root con Docker,
BUILD SUCCESS; 575 test, zero failure/errori/skipped. Superati anche
`git diff --check` e la validazione Hibernate dello schema Flyway.

## Query e dettaglio alert — verifiche FP-035

| Requisito | Evidenza nel modulo fleet-api |
|---|---|
| Filtri singoli e combinati con semantica AND | `MaintenanceAlertRepositoryIntegrationTest` |
| Collection globale e scoped coerenti | `MaintenanceAlertRepositoryIntegrationTest`, `MaintenanceAlertServiceTest` |
| Range `createdAt` inclusivo ai confini | `MaintenanceAlertRepositoryIntegrationTest`, `MaintenanceAlertRequestValidatorTest` |
| Paginazione bounded e ordering stabile `createdAt` + `id` | `MaintenanceAlertPageableFactoryTest`, `MaintenanceAlertRepositoryIntegrationTest` |
| Mapping completo del dettaglio | `MaintenanceAlertMapperTest`, `MaintenanceAlertControllerTest` |
| Veicolo e alert assenti | `MaintenanceAlertServiceTest`, `MaintenanceAlertControllerTest` |
| Enum, UUID, date, sort e pagination invalidi | `MaintenanceAlertControllerTest` |
| Errori infrastrutturali senza leakage | `MaintenanceAlertControllerTest` |
| Endpoint e schemi OpenAPI | `OpenApiIntegrationTest` |

Verifica finale FP-035 del 2026-09-13: `./mvnw verify` dalla root con Docker,
BUILD SUCCESS; 614 test, zero failure/errori/skipped. Superati anche
`git diff --check`, la validazione Hibernate dello schema Flyway e il controllo
di assenza di dichiarazioni locali con `var` nel codice e nei test FP-035.

## Transizioni alert — verifiche FP-036

| Requisito | Evidenza nel modulo fleet-api |
|---|---|
| Matrice delle transizioni consentite, idempotenti e vietate | `MaintenanceAlertStateMachineTest` |
| Timestamp assegnati tramite `Clock` e stabili sui replay | `MaintenanceAlertTransitionAttemptTest`, `MaintenanceAlertTransitionIntegrationTest` |
| Migration V4 e versione iniziale zero | `MaintenanceAlertTransitionIntegrationTest` |
| Controllo `@Version` contro lost update | `MaintenanceAlertTransitionIntegrationTest` |
| Rivalutazione bounded in una nuova transaction | `MaintenanceAlertCommandServiceTest`, `MaintenanceAlertTransitionIntegrationTest` |
| Aggiornamenti concorrenti senza stati impossibili | `MaintenanceAlertTransitionIntegrationTest` |
| Successo, body invalido, not found, conflict e database down | `MaintenanceAlertControllerTest` |
| Endpoint PATCH, request, enum, response ed errori OpenAPI | `OpenApiIntegrationTest` |

Verifica finale FP-036 del 2026-09-14: `./mvnw verify` dalla root con Docker,
BUILD SUCCESS; 641 test, zero failure/errori/skipped. Superati anche
`git diff --check`, la validazione Hibernate dello schema Flyway V4 e il
controllo di assenza di dichiarazioni locali con `var` nel codice e nei test
FP-036.

## Dashboard aggregation API — verifiche FP-037

| Requisito | Evidenza nel modulo fleet-api |
|---|---|
| Clock letto una sola volta, cutoff e conteggi long | `DashboardServiceTest` |
| Finestra e limite non default applicati ai repository (5 minuti, 7 alert) | `DashboardServiceTest.usesConfiguredReportingWindowAndAlertLimitInsteadOfDefaults` |
| Configurazione validate, inclusi limiti e valori mancanti | `DashboardPropertiesTest` |
| Database vuoto, entrambi gli stati, risposta completa | `DashboardApiIntegrationTest`, `DashboardControllerTest` |
| Conteggio distinto, cutoff/now inclusivi, futuro escluso, DISABLED incluso | `DashboardApiIntegrationTest` |
| Tutte le severità, OPEN/ACKNOWLEDGED, esclusione CLOSED, tie-breaker | `DashboardApiIntegrationTest` |
| Limite DB e soli sette campi sintetici | `DashboardApiIntegrationTest`, `OpenApiIntegrationTest` |
| Projection senza caricamento di entity gestite | `DashboardApiIntegrationTest.projectionsDoNotLoadManagedEntities` |
| Quattro SELECT reali anche crescendo da 1 a 101 veicoli/alert | `DashboardApiIntegrationTest.queryCountStaysFourWhenFleetAndAlertVolumeGrow` |
| Snapshot unico con commit concorrente fra le query | `DashboardApiIntegrationTest.allFourQueriesShareSnapshotDuringConcurrentCommit` |
| V5 da schema vuoto e upgrade V4 con conservazione dati | `DashboardApiIntegrationTest` |
| Piano del SQL generato da JPA su 10.000 sample, finestra selettiva | `DashboardApiIntegrationTest.inspectsReportingPlanOnRepresentativeHistory` |
| PostgreSQL fermo: 503, senza vista parziale | `VehicleDatabaseUnavailableIntegrationTest`, `DashboardControllerTest` |
| Redis fermo: dashboard ancora disponibile | `VehicleStateUnavailableIntegrationTest` |
| Unexpected error: 500; response ed errori OpenAPI | `DashboardControllerTest`, `OpenApiIntegrationTest` |

Il conteggio delle query intercetta l'esecuzione SQL sul datasource condiviso
da JDBC e JPA, dopo il setup. La prova concorrente committa nuovi veicoli,
sample e alert da una connessione separata mentre la dashboard sta leggendo:
la richiesta in corso mantiene il primo snapshot; quella successiva vede il
nuovo commit. Non usa sleep per coordinare la concorrenza.

Il laboratorio cattura il SQL effettivamente eseguito da Hibernate e lo passa
a `EXPLAIN (ANALYZE, BUFFERS)`. Non vincola i
test a un piano specifico del planner. Prima di V5, la query globale esaminava
10.000 righe e scartava 9.970 sample fuori finestra, leggendo 184 buffer.
V5 aggiunge un indice che inizia con `observed_at`, mantenendo gli indici scoped
preesistenti. Queste misure su fixture sono diagnostiche e non costituiscono
il report di carico FP-047.

Con V5, lo stesso laboratorio seleziona le 30 righe tramite Index Only Scan,
legge 4 buffer e mostra circa 0,031 ms di execution time, contro circa
0,374 ms della scansione precedente. I tempi dipendono da fixture/cache/host;
la riduzione delle righe esaminate motiva l'indice, senza promettere un SLA.

Verifica finale FP-037 del 2026-10-07: `./mvnw --batch-mode
--no-transfer-progress clean verify` dalla root con Docker, BUILD SUCCESS;
670 test, zero failure/errori/skipped. Superati Compose config con `.env` e
`.env.example`, `git diff --check` e i controlli di stile sui nuovi file Java
(indentazione a quattro spazi, linee entro 100 caratteri, import espliciti e
nessuna dichiarazione locale `var`). Nessun commit eseguito, come richiesto.

FP-037 usa ora Spring Data JPA: aggregazioni nei repository vehicle/telemetry,
conteggio alert derivato e projection `MaintenanceAlertSummary` tramite query
JPQL/HQL. `DashboardService` mappa la projection nel DTO HTTP. La lista usa
`List` con `Pageable` e mantiene il limite nel SQL generato, senza count di
pagina. `projectionsDoNotLoadManagedEntities` verifica quattro query Hibernate
e zero entity caricate. JDBC nei test resta per fixture, conteggio delle
esecuzioni e EXPLAIN; gli adapter JDBC di altre feature restano invariati.

Verifica finale della conversione JPA del 2026-10-07: `./mvnw --batch-mode
--no-transfer-progress clean verify` dalla root, BUILD SUCCESS; 671 test,
zero failure/errori/skipped. Confermati quattro SELECT reali, zero entity
caricate, snapshot concorrente, OpenAPI, guasti PostgreSQL/Redis e upgrade V5.
Il SQL reporting generato da Hibernate usa l'indice V5 con Index Only Scan,
30 righe e 4 buffer nella fixture. Superati Compose config con entrambi i file
env, diff check e stile Java. Nessun commit o modifica dello staging preesistente.


Verifica di chiusura FP-037 del 2026-10-07: nuovo `clean verify` completo,
BUILD SUCCESS; 672 test, zero failure/errori/skipped (117 report Surefire).
Aggiunta la prova dell'uso effettivo della configurazione non default nei
repository: finestra di 5 minuti e limite di 7 alert. Tutti i criteri della
matrice sono soddisfatti sul working tree JPA; FP-037 è tecnicamente chiudibile.
Confermati Compose con entrambi i file env, stile Java e diff check.
La validazione precede il commit FP-037; durante i test lo staging
preesistente è rimasto invariato.
