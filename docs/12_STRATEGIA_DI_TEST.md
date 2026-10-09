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
| ACK/NACK applicativo | Contract/Integration | contratto JSON e decoder coperti; emissione verificata con conferma Kafka tramite `PublishingFrameHandler` |

I test TCP usano porte effimere, risorse racchiuse in `try-with-resources` e
attese con deadline. Non richiedono Docker né porte locali prestabilite. L'ACK
end-to-end non può essere simulato come accettazione definitiva: per contratto
`ACCEPTED` richiede la conferma di pubblicazione Kafka, responsabilità del
`PublishingFrameHandler` di produzione.

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

L'accettazione nominale attraversa servizi e infrastruttura reali, senza mock
applicativi e senza inserire fixture direttamente nel database:

1. registra il veicolo via REST e verifica `201`, identificativo e risorsa creata;
2. invia due telemetrie distinguibili sulla stessa connessione TCP e verifica
   gli ACK `ACCEPTED`, versione e `messageId` correlati;
3. osserva per ciascun messaggio il record Kafka, key `vehicleId`, identificativi,
   timestamp e payload, senza modificare gli offset del group applicativo;
4. verifica il singolo sample PostgreSQL e la corrispondenza dei dati;
5. verifica contenuto completo e TTL della projection Redis **prima** della GET
   State API, evitando che il read repair mascheri un update processor mancante;
6. verifica HTTP `200`, tutti i campi della State API, freshness e cache hit senza
   fallback PostgreSQL, per entrambi gli aggiornamenti;
7. riconcilia sample e record raw, assenza di esiti terminali/alert inattesi e lag
   drenato, poi verifica il cleanup delle sole risorse del test.

Il test usa progetto, porte e volumi isolati, attese finite e timestamp confrontati
come istanti considerando la precisione PostgreSQL. Non sostituisce il gate
prestazionale FP-047. Il verificatore e le istruzioni di esecuzione sono in
[infrastructure/e2e](../infrastructure/e2e/README.md).

### E2E-002 — Alert

1. registra un veicolo via REST e invia via TCP un valore oltre soglia;
2. verifica ACK, record raw, sample e un alert con `sourceMessageId`, tipo,
   severity e stato `OPEN` attesi, esposto da collection e dettaglio REST;
3. ripubblica lo stesso frame con lo stesso `messageId`, verificando che un nuovo
   record raw raggiunga il processor;
4. attende la classificazione positiva del duplicato e il commit dell'offset
   prima di confrontare gli esiti: conteggi invariati subito dopo ACK non bastano;
5. verifica che sample e alert restino identici, inclusi ID e timestamp;
6. riconosce l'alert via REST (`ACKNOWLEDGED`) e ripete il replay, verificando che
   non venga riaperto e che il timestamp dell'operatore sia conservato;
7. riconcilia tre pubblicazioni raw, un sample, un alert, due duplicati e nessun
   rejected/DLT, quindi verifica cleanup delle sole risorse isolate.

Il replay E2E è una nuova pubblicazione dello stesso messaggio tramite gateway:
non è un reset degli offset, un replay DLT o una prova di crash del processo.
La verifica usa servizi reali e riusa l'harness nominale; istruzioni in
[infrastructure/e2e](../infrastructure/e2e/README.md#alert-e-replay--fp-051).

### E2E-003 — Restart del processor

Distinguere tre prove: retry dopo eccezione, restart del listener nella stessa
JVM e restart del processo. Le prime due non dimostrano la recovery dopo la
perdita del processo. `TelemetryOffsetSemanticsIntegrationTest` copre i confini
commit/offset con errori simulati e il restart del listener; FP-044 richiede
anche `TelemetryProcessorRestartIntegrationTest`, con JVM distinte e
Kafka/PostgreSQL/Redis isolati tramite Testcontainers.

1. Pubblicare un evento che genera sample e alert, registrandone
   `messageId`, topic, partition e offset.
2. Terminare bruscamente la JVM dopo il commit dell'aggregato PostgreSQL e
   prima del ritorno del listener, senza shutdown hook o commit graceful.
3. Verificare che sample e alert esistano e che l'offset committed del gruppo
   non abbia superato quello del record. L'offset committed indica il prossimo
   record da consumare: dopo il successo deve valere `sourceOffset + 1`.
4. Avviare una nuova JVM con lo stesso consumer group, senza ripubblicare
   l'evento e senza spostare gli offset. Verificare la riconsegna della stessa
   posizione Kafka e il successivo avanzamento dell'offset.
5. Confrontare cardinalità, identità, valori e timestamp dei sample/alert
   prima e dopo il replay: l'aggregato deve rimanere invariato.
6. Verificare log correlati di persistenza/duplicato e contatori per processo:
   primo processo `persisted=1`, secondo `duplicates=1` e `persisted=0`.
7. Dopo il commit offset riavviare ancora; un nuovo evento sulla stessa
   partition deve essere elaborato senza riconsegnare quello già committed.

La terminazione deterministica è una fixture esclusivamente di test; non
introduce interruttori di crash nell'applicazione distribuita. Le attese hanno
deadline e tutti i processi figli devono essere terminati anche in caso di
fallimento. I contatori Micrometer ripartono nella nuova JVM: non confrontare
valori cumulativi come se fossero uno storico durevole degli eventi.

La prova copre l'idempotenza dell'aggregato, non l'atomicità fra PostgreSQL,
Redis e Kafka. Il crash scelto è dopo il ritorno del servizio, quindi anche
dopo il tentativo Redis; il crash fra commit DB e update Redis resta un
confine distinto e non dimostra la recovery automatica della cache.

### E2E-004 — Redis non disponibile

1. Persistire telemetria in PostgreSQL, popolare Redis e verificare una lettura
   dello stato servita dalla cache; misurarne la latenza di riferimento.
2. Fermare realmente Redis, mantenendo PostgreSQL e API attivi. Il guasto
   tramite proxy è una prova complementare, non sostituisce stop/start.
3. Verificare HTTP 200 e stato completo coerente con PostgreSQL, senza
   modifiche ai sample. I contatori devono distinguere fallback, failure di
   lettura Redis e failure del tentativo di repair; un errore non è un miss.
4. Riavviare lo stesso Redis, senza ricreare ApplicationContext o connection
   factory. Dopo la riconnessione verificare cache vuota, read repair con
   JSON e TTL validi, quindi cache hit senza query PostgreSQL.
5. Verificare separatamente che il processor possa persistere un evento
   durante il guasto e aggiornare Redis con un evento successivo dopo il
   ripristino, usando lo stesso servizio e la stessa connection factory.

La risposta durante il guasto può costare un timeout di lettura e uno di
repair oltre alle query DB. Un rifiuto immediato della connessione può invece
fallire rapidamente: non imporre che ogni outage sia più lento di ogni hit.
Misurare entrambe le latenze; un blackhole controllato deve dimostrare
l'attesa del timeout e una conclusione entro il budget generoso del test,
senza trasformare tale budget in SLA di produzione.

FP-045 verifica il restart con endpoint stabile. Ricreare Redis con un nuovo
IP, cambiare endpoint o forzare refresh DNS è un caso distinto: la recovery
del client in tali condizioni non è dimostrata dal solo stop/start.

### E2E-005 — Input TCP invalido

1. invia frame malformato;
2. verifica rifiuto;
3. verifica gateway ancora disponibile.

### E2E-007 — Failure scenarios del backend (FP-052)

L'accettazione usa servizi reali in un progetto Compose sacrificabile, con
readiness iniziale, traffico controllato e cleanup anche dopo fallimento:

- Processor fermo: il gateway conferma Kafka; il record resta pending senza
  nuovo sample o projection. Riavviare il processor con lo stesso group deve
  drenare il backlog e aggiornare SQL/Redis/API senza ripubblicazione.
- Redis fermo: il processor persiste e committa l'offset; l'API risponde da SQL.
  Osservare failure della projection e failure/fallback/repair API. Dopo stop/start
  Redis, verificare read repair, cache hit e nuovo update processor, senza restart
  delle applicazioni e senza estendere la prova al cambio IP/DNS.
- Frame con lunghezza zero: la connessione viene chiusa senza ACK; counter di
  rifiuto incrementato e nessun effetto Kafka/SQL/Redis. Un successivo frame valido
  deve attraversare l'intera pipeline, senza restart gateway.
- Kafka fermo: gateway/processor non ready ma vivi; nessun falso ACCEPTED. Il
  rifiuto applicativo è UPSTREAM_UNAVAILABLE correlato. Dopo il ripristino,
  ritentare lo stesso messageId e verificare persistenza unica e stato via API,
  con recovery dei client senza restart applicativo.

Un timeout di pubblicazione Kafka può lasciare un send in-flight: non dedurre
assenza definitiva dal NACK. Riconciliare raw, sample, duplicati e terminal topic
solo dopo il recupero e il drain; tollerare una consegna tardiva purché idempotente.
Il budget generoso della socket comprende anche l'eventuale attesa metadata del
producer e non equivale a uno SLA. Lo stop graceful del processor non sostituisce
la prova di crash FP-044 descritta in E2E-003. Verificatore e comandi:
[infrastructure/e2e](../infrastructure/e2e/README.md#failure-scenarios--fp-052).

### E2E-006 — Rifiuto asincrono del veicolo

1. invia telemetria per un veicolo sconosciuto o disabilitato;
2. verifica che il gateway restituisca `ACCEPTED` dopo la pubblicazione Kafka;
3. verifica il rejection event su `telemetry.rejected.v1`;
4. verifica l'assenza di sample, aggiornamenti Redis e alert.

## 5. Failure injection

FP-046 verifica il collegamento **Fleet API → Redis** tramite Toxiproxy
2.5.0 e dipendenze Testcontainers isolate. I guasti sono applicati downstream
(risposte Redis → API); API e connection factory restano attive durante
iniezione e recovery. Non estendere questa copertura a Kafka, PostgreSQL,
carico o cambio IP/DNS senza prove dedicate.

| Guasto | Parametri della fixture | Criterio |
|---|---|---|
| Latency | 50 ms, jitter zero; timeout client 200 ms | Cache hit HTTP 200 identico, ritardo osservato, nessun fallback/failure o query DB |
| Timeout | Blackhole `timeout=0`, timeout client 200 ms | Vera eccezione timeout, HTTP 200 da DB, failure read/repair e fallback distinti |
| Reset | `reset_peer`, timeout zero | Connessione attiva interrotta e reset confermato da sonda nella rete Docker; HTTP 200 via fallback e failure registrate |
| Bandwidth | 32 KB/s, trasferimento di fixture 65.536 byte | Risposta bulk completa e rallentata, stato REST valido, nessuna corruzione |

Ogni guasto viene rimosso in `finally`; la riconnessione usa polling con
deadline. Dopo la rimozione si prova read repair con JSON/TTL validi e cache
hit senza query DB, nella stessa API. Tutti i sette scenari di
`VehicleStateRecoveryIntegrationTest` confrontano anche sample e alert prima
e dopo: i guasti della cache non devono modificare i dati di dominio.

Per bandwidth si usa una chiave di trasferimento separata, con TTL e cleanup,
senza alterare il JSON dello stato. Il suo payload rende misurabile il limite:
il piccolo JSON di dominio può ancora essere letto prima del timeout e non
deve necessariamente causare fallback. La verifica REST accetta hit o
fallback riuscito, poi dimostra recovery. La sonda bulk usa socket RESP con
deadline e misura il trasferimento; non usa il timeout client dell'API.

I budget temporali sono margini della fixture, non SLA: latency fra 40 ms e
2 s, blackhole REST fra 150 ms e 5 s, bulk limitato fra 1 s e 8 s. I risultati
per i quattro guasti sono registrati con parametri, direzione, durata ed esito
in `target/fp046-evidence/faults.jsonl`; i report di esecuzione restano fuori
da `docs`. Il timing REST tramite MockMvc non include la rete HTTP del client.
Docker Desktop può tradurre un reset in EOF nel forwarding verso l'host:
la prova reset verifica perciò sia l'interruzione della socket già attiva
sia l'errore di reset osservato da `redis-cli` nella rete isolata Docker.

## 6. Carico di riferimento

```text
vehicles: 50
message interval: 2 secondi
duration: 5 minuti
duplicate probability: 2%
disconnect probability: 1%
```

### Contratto di accettazione FP-047

La baseline usa 50 connessioni persistenti senza fault. La prova mista ripete
lo stesso carico con seed dichiarato: per ogni frame unico, una decisione
indipendente al 1% interrompe la socket a metà payload, quindi riconnette e
invia integralmente lo stesso messaggio; una decisione al 2% reinvia un frame
completo già accettato, con identici `messageId` e sequence. Le percentuali sono
probabilità per frame, non quote esatte; riportare i conteggi osservati.

Gli slot nominali sono t=0,2,...,298 s: 150 frame unici per veicolo, 7.500
complessivi, 25 frame/s medi. Duplicati e payload parziali sono traffico aggiuntivo.
La schedulazione usa tempo monotono e non somma il tempo di invio al periodo.
Ritardi di almeno un intervallo invalidano la cadenza; lateness osservata e
throughput sono conservati. Warm-up e drain sono esterni ai 300 s misurati.
La prova usa ACK length-prefixed validi e correlati, ma verifica separatamente
la persistenza: uguaglianza fra messageId offerti, accettati e righe SQL uniche,
nessun alert per il profilo normale, nessun messaggio rejected/dead-letter e
lag consumer finale zero entro 60 s. Errori/ACK ambigui restano errori del run.

Il p95 gateway → commit usa i campi post-commit definiti in
[Observability](11_OBSERVABILITY.md), con un campione valido per ogni frame
unico e percentile nearest-rank. Obiettivo locale RNF-003: < 2 s. Non usare il
timer del solo handler, differenze `processed_at - received_at` o somme di p95.
Smoke e warm-up non certificano il carico completo.

L'harness `infrastructure/load/run.py` usa uno stack Compose isolato, porte
dinamiche e volumi propri, rimosso al termine anche in caso di errore.
I budget operativi della prova, distinti da SLO di produzione, sono: 2 CPU per
container; memoria massima 512 MiB per applicazione e PostgreSQL, 128 MiB Redis,
1 GiB Kafka; heap massimo applicazioni 65% del limite container. Il working set
container e l'heap usato devono restare sotto il 90% dei rispettivi massimi;
CPU campionata entro 205% (margine di misura sul limite di 2 CPU), nessun OOM,
restart o arresto inatteso. Fra campioni risorse si attendono 5 s, oltre al tempo di raccolta; il lag è
misurato ogni sei campioni e a fine drain. La cadenza effettiva si ricava dai
timestamp conservati. Conservare memoria/CPU, metriche JVM/GC/pool/connessioni,
distribuzione latenze, conteggi, ambiente e commit. Una prova di cinque minuti
dimostra il rispetto osservato dei budget, non l'assenza assoluta di leak.

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

Le verifiche delle query devono usare SQL effettivamente generato da JPA,
fixture rappresentative e piani `EXPLAIN (ANALYZE, BUFFERS)`. Non vincolare
il test a un piano specifico del planner o a tempi dipendenti dalla macchina.
L'indice V5 deve supportare la selezione globale per `observed_at`, conservando
gli indici per le query scoped. Le misure sulle fixture non costituiscono
un criterio di carico o uno SLA.

## Uniformazione degli adapter PostgreSQL a JPA — FP-018/031/033

| Requisito | Evidenza |
|---|---|
| Latest-state: filtro veicolo e ordine observedAt/sequenceNumber/id, limite DB di una riga | VehicleStateApiIntegrationTest |
| Latest-state: una sola query e zero entity gestite caricate | VehicleStateApiIntegrationTest.latestSampleUsesSingleProjectionQueryWithoutLoadingManagedEntities |
| Cache hit senza PostgreSQL, fallback/repair e Redis indisponibile | VehicleStateApiIntegrationTest, VehicleStateCacheHitDatabaseUnavailableIntegrationTest, VehicleStateUnavailableIntegrationTest |
| Registry: ACTIVE, DISABLED, veicolo assente | PostgreSqlVehicleRegistryIntegrationTest |
| Soglia manutenzione per ACTIVE/DISABLED e veicolo assente | PostgreSqlVehicleRegistryIntegrationTest |
| Lookup scalari/projection: due query e zero entity caricate | PostgreSqlVehicleRegistryIntegrationTest.readsScalarAndProjectionWithoutLoadingManagedVehicleEntities |
| Rilettura stato e soglia dopo modifica, senza dati da entity in cache | PostgreSqlVehicleRegistryIntegrationTest.observesChangedStatusAndMaintenanceThresholdOnFollowingLookup |
| Validazione schema e confini commit/offset/Redis invariati | TelemetrySamplePersistenceIntegrationTest, TelemetryAggregateWriterIntegrationTest, TelemetryOffsetSemanticsIntegrationTest, TelemetryPipelineIntegrationTest |

Le fixture continuano a usare JDBC; il codice di produzione dei tre adapter
usa repository JPA. Il read model veicoli del processor è locale e @Immutable;
il repository espone soltanto letture scalari/projection.

## Contratti delle API di lettura

Le verifiche di state, history e collection/dettaglio alert devono coprire
il contratto REST e la corrispondenza con OpenAPI usando route e database reali.

| Criterio | Copertura richiesta |
|---|---|
| State | Hit/fallback, freshness, timestamp, dato assente, UUID e struttura degli errori |
| History | Range inclusivo, from=to, isolamento veicolo, ordering stabile, ultima pagina e pagina oltre ultima |
| Alert | Filtri individuali e combinati AND, confini inclusivi/aperti, ASC/DESC e tie-breaker |
| Global/scoped | Collection globale vuota distinta dal 404 per veicolo assente |
| Paginazione | Limiti, default, totali filtrati e metadati su dati invariati |
| Schema response | Campi required, nullabilità, UUID/date-time/int64 e content type |
| Parametri OpenAPI | Min/max/default/required e schema degli errori di ogni route |
| Binding | Omissione lecita e default, valori invalidi, valori vuoti/blank e duplicati identici |

History e collection alert rifiutano parametri scalari vuoti/blank e ripetuti
con `400 REQUEST_INVALID`, prima della conversione Spring e dell'applicazione
dei default. Il contratto della ricerca testuale veicoli è distinto.
Non estendere questa regola ai body HTTP.

Preferire assert mirati sugli schemi ai confronti dell'intero documento OpenAPI.
Validare codice/status, details, path e timestamp senza fissare il testo
localizzato degli errori. Verificare paginazione senza buchi su dati invariati;
non promettere snapshot fra richieste. Con `Page`, non imporre un numero fisso
di COUNT, che Spring Data può evitare in alcuni casi.

Scelta delle query: [ADR-012](adr/ADR-012-STRATEGIA-ACCESSO-DATI-JPA.md).

## Cache resilience — verifiche FP-039, FP-045 e FP-046

VehicleStateRecoveryIntegrationTest usa PostgreSQL/Redis reali e Toxiproxy,
con la stessa ApplicationContext e connection factory durante guasto e recovery.
La dipendenza Toxiproxy è soltanto test e segue il BOM Spring Boot.

| Requisito | Evidenza |
|---|---|
| Connessione interrotta: fallback 200, failure read/repair distinte | reconnectsRepairsAndServesCacheHitAfterConnectionOutage |
| TCP aperto, risposte bloccate: vero timeout di comando e fallback 200 | realCommandTimeoutFallsBackAndRecoversAfterNetworkFaultIsRemoved |
| Miss riuscito, guasto solo durante repair: risposta PostgreSQL preservata | repairConnectionFailureAfterSuccessfulMissDoesNotChangePostgresResponse |
| Stop/start reale Redis, stato coerente, nessun restart API, repair e hit senza DB | realRedisStopAndRestartFallsBackThenRepairsWithoutRestartingApi |
| Latenza controllata con cache hit valido e nessun accesso DB | controlledLatencyKeepsCacheHitAndRecoversWithoutRestart |
| Reset TCP osservato, fallback e recovery | tcpResetIsObservedAndStateFallsBackThenRecovers |
| Banda limitata misurabile, risposta integra e recovery | bandwidthLimitSlowsMeasuredTransferAndPreservesStateAndRecovery |
| Recovery senza restart API, repair JSON/TTL, hit senza query veicolo/sample | Tutti i sette scenari di VehicleStateRecoveryIntegrationTest |
| Persistenza durante outage e nuovo evento proiettato dopo restart Redis, factory invariata | TelemetrySamplePersistenceIntegrationTest.persistsDuringRedisOutageAndProjectsNewEventAfterRedisRestart |
| Redis fermo e guasto simultaneo DB, hit con PostgreSQL fermo | VehicleStateUnavailableIntegrationTest, VehicleStateCacheHitDatabaseUnavailableIntegrationTest |
| Warning limitati, nessun payload/stacktrace/tag ad alta cardinalità | VehicleStateObservabilityTest |
| Protezione da repair concorrente più vecchio, JSON invalido e TTL | VehicleStateApiIntegrationTest, RedisLatestStateProjectionIntegrationTest |

Il proxy mantiene l'endpoint e i guasti vengono rimossi in finally. La
riconnessione usa polling con limite massimo di 10 secondi, senza sleep fissi.
La verifica di disponibilità chiama l'adapter direttamente, fuori dai contatori
REST; ogni fase confronta delta metriche della singola richiesta.
La chiave è ispezionata dal canale di controllo Redis indipendente dal proxy.
Nel caso timeout, dopo il ripristino si elimina la chiave di fixture per
provare esplicitamente il repair della richiesta successiva: il timeout client
non è assunto come prova di mancata esecuzione di un comando lato server.
I timeout Redis sono 200 ms nei test, senza imporre un SLA alla response REST.
La prova blackhole misura anche una baseline cache hit e verifica una durata
REST fra 150 ms e 5 s: margine rispetto al timeout di comando di 200 ms e
budget di test, non garanzia operativa. La prova stop/start misura la durata
senza imporre un confronto relativo soggetto a rumore di esecuzione.

## FP-040 — Logging e correlazione

Verificare serializzazione con encoder ECS reale (timestamp, livello, versione,
campi numerici, key/value e MDC), UUID HTTP assente/valido/invalido/ripetuto,
header su errori e ripristino del contesto in caso di failure. La suite completa
verifica inoltre privacy e rate limiting cache/projection, esiti gateway,
retry/dead-letter Kafka e simulator. I log di integrazione devono identificare
le applicazioni con `service.name` e rimanere JSON valido.

## FP-041 — Metriche custom

Verificare tentativo fallito → retry persistito → replay duplicato: tre tentativi,
una persistenza, un duplicato e un solo timer per esito. Con MockClock simulare
100 ms fino al commit e 900 ms per Redis, verificando misure separate. Rifiuti
dominio non devono produrre persistenze; failure Redis resta un successo DB.

Nel gateway verificare publish confirmed/failed, ACK/NACK, timeout e interruzione,
framing invalido/malformed/truncated e EOF normale escluso dai rifiuti. Controllare
reason finite e nessun ID come tag. Con l'applicazione reale, verificare export
count/sum/bucket in secondi, bucket +Inf e 2 s, HTTP route normalizzata per UUID
diversi e contatori cache con Redis fermo/riavviato. La verifica dello scrape
Prometheus e dei target UP resta FP-042, non è sostituita da una GET all'endpoint.

La suite automatica comprende `PrometheusHistogramConfigurationTest` nei tre
servizi: carica l'effettivo `application.yaml` e l'autoconfigurazione Micrometer,
registra durate deterministiche ed esamina l'export del registry Prometheus reale.
Verifica count/sum in secondi, bucket cumulativi e +Inf, limiti min/max e override
di `METRICS_TIMER_MIN/MAX/BUCKETS`, senza duplicare la configurazione nel test.
`HttpRequestMetricsIntegrationTest` usa il controller veicoli e il filtro di
osservazione MVC reali, con il servizio applicativo simulato: UUID diversi e
risposte 200/404 condividono la route normalizzata. UUID, request ID, query e
dati della risposta non devono comparire nell'export. Questi test sono parte
di `clean verify`; le prove Compose restano complementari per deployment e Redis.

## Raccolta metriche e dashboard tecnica

Le verifiche di osservabilità devono distinguere l'export applicativo dalla
raccolta sul server Prometheus e dall'esecuzione delle query tramite Grafana.

- Target, URL, intervallo e timeout coerenti con la configurazione del progetto.
- Metriche JVM e custom interrogabili tramite il server di raccolta.
- Target down e ripresa dello scrape dopo il ripristino.
- Datasource/dashboard/folder con UID stabili e provisioning su storage vuoto.
- Aggiornamento del datasource precedente e riavvio senza duplicati.
- Query dei pannelli eseguibili, unità e legende coerenti col catalogo metriche.
- Traffico TCP/REST, replay e rifiuti con esiti osservabili nei pannelli pertinenti.
- Assenza di traffico o campioni distinta da zero e da un target DOWN.
- Verifica visiva e assenza di errori del client Grafana.

Le serie di failure presenti a zero sono valide; non dimostrano l'esecuzione
dei percorsi di guasto. L'esito dello scrape non sostituisce readiness o salute
delle dipendenze. L'assenza di dati non deve essere mascherata da valori fittizi.

## Health e readiness — FP-048

Le verifiche usano la configurazione applicativa effettiva: probe HTTP distinte,
gruppi con contributor esistenti, Redis escluso anche dall'health generale e
payload senza dettagli sensibili. Test con soli indicatori mock non sostituiscono
la verifica di outage/recovery delle dipendenze reali.

| Scenario | Risultato richiesto |
| --- | --- |
| Tre servizi sani | root/liveness/readiness UP, HTTP 200 |
| Redis fermo, DB/Kafka sani | Tutte le probe 200; State API fallback 200 con ultimo sample; processor persiste e osserva failure projection |
| Avvio API/processor con Redis già fermo | Probe 200 dopo completamento startup; fallback servibile |
| PostgreSQL fermo a runtime | API/processor root e readiness 503; liveness 200; gateway ready |
| PostgreSQL fermo, hit cache | State API 200 anche se readiness API 503; miss richiede DB e restituisce 503 |
| Kafka fermo | Gateway/processor root e readiness 503; liveness 200; API ready |
| Ripristino DB/Kafka | Probe tornano 200 e telemetria persiste senza restart delle applicazioni |
| Listener TCP terminato | Gateway root/readiness 503, liveness 200 |
| Listener TCP assente/disabilitato | Indicatore TCP DOWN |
| Consumer assente/arrestato | Indicatore consumer DOWN; endpoint root/readiness 503 per container arrestato |
| Consumer senza partizioni | Non dichiarare DOWN solo per assenza di assegnazioni |
| REFUSING_TRAFFIC / BROKEN | Readiness / liveness rispettivamente 503, stato ripristinato a fine test |
| Metadata incompleti, topic/leader assente, timeout Kafka | Indicatore DOWN, nessun dettaglio sensibile, attesa limitata |

`GatewayHealthIntegrationTest` verifica HTTP e stati di disponibilità con Kafka
reale; `ProcessorHealthIntegrationTest` verifica arresto/ripresa del consumer
con PostgreSQL/Kafka e preserva il group ID configurato. I test unitari degli
indicatori verificano leader/topic, cache, timeout, interrupt e shutdown.
Il profilo test processor esclude Redis e non dimostra la sua opzionalità in
production: questa è verificata da `infrastructure/health/verify.py`, tramite
HTTP reale su stack Compose isolato con stop/start delle dipendenze.

Le probe raccolte nel verificatore devono rispondere entro 5 s con i default;
conservare status/body/durata e verificare ritorno UP senza restart DB/Kafka.
I test non certificano blackhole JDBC arbitrari. Le regressioni riusano fallback,
recovery Redis, offset e crash recovery già coperti in FP-044/045/046. Per il
carico FP-047 è sufficiente uno smoke dell'avvio tramite nuova readiness: non
si ripete la prova da dieci minuti senza modifiche al percorso dei frame.
Sul broker isolato appena creato, una partizione vuota senza offset committed
ha lag zero; una partizione non vuota ancora senza commit conta tutti gli offset
dal principio del log. Il simbolo `-` della CLI non deve far fallire lo smoke
né nascondere record ancora da confermare.
