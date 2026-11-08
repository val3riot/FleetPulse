# Strategia di test

Verificare comportamento osservabile, invarianti e recupero dai guasti.
Ogni test deve controllare i propri dati, dipendenze e cleanup; un fallimento
non va mascherato con retry della suite o attese arbitrarie.

## Livelli di verifica

| Livello | Oggetto | Isolamento |
|---|---|---|
| Unitario | Codec, regole di dominio, parser, classificazione errori | Dipendenze controllate, clock simulato |
| Integrazione | Transazioni, repository, protocolli e client HTTP | Testcontainers o backend HTTP di test |
| End-to-end | Gateway, Kafka, processor, database e API | Stack Compose e dati propri |
| Carico | Cadenza, persistenza, risorse e latenza | Ambiente dichiarato, seed e drain |
| Accessibilità | Semantica, focus, contrasto e interazione | Test automatici più browser/tecnologie assistive |

Gli stub non attestano il contratto di un servizio reale. I report devono
distinguere test simulati, componenti infrastrutturali reali e prove manuali.

## Backend

### Unit test

- Codec TCP: frame/header/payload divisi, concatenazione, lunghezze eccessive,
  EOF incompleto, JSON malformato e versione protocollo non supportata.
- Eligibility veicolo: sconosciuto/disabilitato, classificazione e assenza di
  side effect per eventi rifiutati.
- Regole alert: valori sotto/uguali/sopra soglia, più alert, determinismo,
  invarianti e configurazione invalida.
- Transizioni alert, idempotenza e tentativi limitati; eventi ritardati e
  confronto della latest-state projection.

### Integrazione e contratti

Usare PostgreSQL, Kafka, Redis e Toxiproxy reali tramite Testcontainers.

| Area | Invarianti da verificare |
|---|---|
| PostgreSQL/JPA | Migrazioni da DB vuoto, unique messageId e source/type, rollback, concorrenza e projection tipizzate |
| Fleet API | HTTP/body/header, validazione, errori uniformi, 404/409/503, query/paginazione e coerenza OpenAPI |
| Alert | Transizioni ammesse/vietate, timestamp, idempotenza senza riscrittura, optimistic locking e rivalutazione bounded |
| Kafka | Record key, consegna, replay/duplicati, offset, rejection e dead-letter |
| Redis | Codec, TTL reale, aggiornamento concorrente, miss/failure, fallback PostgreSQL e repair |
| TCP | Socket reali, connessioni concorrenti, backpressure, timeout, disconnect e ACK correlati |
| Osservabilità | Log strutturati/MDC, cardinalità bounded, contatori, timer e assenza di dati sensibili |

I test HTTP devono esercitare i vincoli reali PostgreSQL e non soltanto mock di
repository. Gli esempi JSON e il formato errori devono rispettare il contratto
pubblicato. Per la State API verificare fast path Redis, hit stale conservato,
fallback deterministico, repair best-effort e assenza di query superflue.

Il repair non sovrascrive una misura concorrente più recente. Un fallimento
cache non annulla una risposta PostgreSQL riuscita. Verificare log limitati,
metriche coerenti e rilascio di connessioni/thread dopo il guasto.

### E2E-001 — Flusso nominale

Registrare un veicolo, inviare telemetria TCP e correlare ACK, messageId,
persistenza SQL, stato e API. Un replay conserva gli stessi identificativi e
non crea righe o alert duplicati. Confrontare timestamp come istanti, rispettando
la precisione PostgreSQL. Lo smoke non sostituisce il test di carico.

### Alert e replay

Verificare tipi/severità, sourceMessageId, unicità source/type e query globali o
scoped. Ripubblicare lo stesso messaggio dal gateway; nessun secondo alert.

### E2E-003 — Restart del processor

La semantica offset richiede sia errori simulati sia crash con JVM distinte,
PostgreSQL/Kafka riusati e arresto nel confine critico del commit. Lo shutdown
graceful non dimostra recovery da perdita del processo. Dopo restart verificare
riconsegna, persistenza unica e avanzamento corretto degli offset; un frame già
committed non deve bloccare quelli successivi della stessa partition.

### Cache resilience

Interrompere la rete API→Redis con endpoint stabile: refusal/reset, timeout e
blackhole devono rispettare i budget, mantenere fallback e consentire recupero
nella stessa istanza. Dopo repair riuscito, un hit non consulta PostgreSQL.
Il solo stop container non copre tutte le modalità di guasto; cambio IP/DNS è
un caso diverso dal restart sullo stesso endpoint.

### Outage Kafka e guasti controllati

Provare Kafka caldo e freddo, fault downstream e restart. Per la baseline locale,
NACK entro budget decisione 5s più 1s di margine trasporto, socket timeout 7s.
Separare rifiuto applicativo, esito incerto e consegna tardiva; attendere drain
e verificare idempotenza prima di dichiarare perdita o duplicazione.

Scollegare un client a metà frame non deve bloccare le altre connessioni. Un
veicolo rifiutato asincronamente può avere già ricevuto ACCEPTED dal gateway:
rejection Kafka e database attestano l'esito di dominio.

Comandi e teardown: [verificatori E2E](../infrastructure/e2e/README.md).

### Health e readiness

Distinguere avvio HTTP, liveness e readiness. Verificare start a freddo,
indisponibilità e recupero delle dipendenze, probe prive di dati sensibili e
chiusura completa degli stack di prova. Con i default le probe devono rispondere
entro 5s; un proxy/refusal HTTP non certifica un blackhole JDBC arbitrario.

Procedure: [health](../infrastructure/health/README.md),
[observability](11_OBSERVABILITY.md), [operazioni](../infrastructure/operations/README.md).

## Carico di riferimento

| Parametro | Valore |
|---|---|
| Veicoli/connessioni persistenti | 50 |
| Periodo per veicolo | 2s |
| Finestra misurata | 300s, warm-up e drain esclusi |
| Frame unici nominali | 7500, slot t=0,2,…,298s |
| Duplicati nel profilo misto | Probabilità 2% per frame |
| Disconnect parziale nel profilo misto | Probabilità indipendente 1% per frame |

Schedulare con clock monotono senza sommare il tempo di invio al periodo;
ritardi di almeno un intervallo invalidano la cadenza. Dopo un payload parziale,
riconnettere e inviare integralmente lo stesso messaggio. Duplicati e frammenti
sono traffico aggiuntivo, non nuovi eventi. Conservare seed e conteggi osservati:
le probabilità non sono quote esatte.

ACK validi e correlati non bastano: messageId offerti, accettati e righe SQL
uniche devono coincidere; nessuna rejection/dead-letter o alert nel profilo
normale; lag finale zero entro drain 60s. ACK ambigui restano errori del run.

Il p95 gateway→commit usa un campione post-commit per evento unico e percentile
nearest-rank, obiettivo locale RNF-003 inferiore a 2s. Non sommare p95 di servizi
né usare soltanto il tempo del handler o processed_at−received_at.

Budget della prova: 2 CPU per container; memoria 512MiB per applicazioni e
PostgreSQL, 128MiB Redis, 1GiB Kafka; heap applicativo massimo 65%. Working set e
heap usato sotto 90% dei rispettivi massimi, CPU campionata entro 205%, nessun
OOM/restart inatteso. Risorse ogni 5s oltre il tempo di raccolta; lag ogni sei
campioni e a fine drain. Riportare cadenza effettiva, ambiente, commit, metriche
JVM/GC/pool e distribuzione delle latenze. Una prova breve non certifica assenza
assoluta di leak. Stack isolato e volumi propri, rimossi anche su errore.

Istruzioni: [harness di carico](../infrastructure/load/README.md).

## Frontend

### Componenti, routing e HTTP

Usare TestBed e HttpTestingController con client applicativo reale quando si
verificano metodo/query/body, retry, timeout o cancellazione. Concludere con
verify delle richieste inattese. Stub FleetApi per test focalizzati su UI/routing.
Le integrazioni critiche devono attraversare form e link con dati validi.

Verificare contenuto, focus, URL, comandi disponibili e recupero osservabile.
Separare bozza da filtri applicati; nuova ricerca non riusa dati del vecchio
contesto. Errori non diventano vuoti; esiti incerti non reinviano POST/PATCH.
Clock simulato per retry/polling, evento Router per navigazione: nessuno sleep
arbitrario. Ogni test possiede dati e cleanup di HTTP, timer, listener e processi.

Polling: prima lettura, attesa dopo risposta, nessuna sovrapposizione, pausa,
visibilità, cleanup, cambio identità, snapshot conservato e recupero con dati
identici. I test non devono introdurre un secondo livello di retry applicativo.

### Coverage

`test:coverage` usa il provider V8 compatibile con Vitest. Include il codice
applicativo, esclude test/stub e diagnostica dev; genera HTML, JSON summary e
LCOV in `coverage/fleet-dashboard/`, directory ignorata dal versionamento.

Usare righe e branch per trovare percorsi scoperti, poi aggiungere test dei
rischi reali. Non nascondere codice di prodotto per migliorare il report, né
scrivere test di implementazione soltanto per raggiungere una percentuale.
I report non misurano qualità delle asserzioni o comportamento nativo del browser.

### Fixture manuale e produzione

La fixture HTTP UI usa dati fissi in memoria e scenari per endpoint, senza
inoltro a servizi reali. Ogni test Node avvia un'istanza su porta effimera e ne
chiude socket/timer. Le prove manuali usano proxy e porta separati; ogni
richiesta, retry compresi, consuma un passo della sequenza.

Rete status 0 si simula nel backend di test o con DevTools Offline: un proxy 502
è un errore HTTP diverso. La fixture non sostituisce i test di contratto BE.

Dopo build, controllare metadati/grafo moduli e file realmente emessi: entrypoint,
chunk lazy e risorse HTML presenti, assenza di diagnostica/stub/fixture, stats
e source map fuori dagli asset distribuiti. Il controllo deve essere testato
su un output valido e su artefatti contaminati/incompleti isolati.

### Accessibilità

Lint template, axe WCAG A/AA, contrasto dei token e test focus accompagnano i
flussi funzionali. JSDOM non misura rendering, scroll, dialog nativo o screen
reader; dichiarare i limiti e affiancare le [prove manuali](17_ACCESSIBILITA_FRONTEND.md).
Nessuna suite browser è implicita nel gate dei componenti.

## Quality gate

Backend: JDK 21, Maven 3.9.13–3.x, `clean verify`; convergenza dipendenze, warning
javac motivati, formatter comune e test. Test Python dei verificatori e Docker
E2E sono verifiche separate. Il controllo dipendenze non sostituisce un audit.
Istruzioni: [quality gate](../infrastructure/quality/README.md).

Frontend: `npm run verify` esegue lint, formato, contrasto, test con coverage,
fixture HTTP, test del controllo produzione, build e controllo artefatti.
`npm run audit` resta separato. Comandi nel [README](../frontend/fleet-dashboard/README.md).

Verificare inoltre coerenza di link, configurazione ed esempi REST con i
[controlli documentali](../infrastructure/consistency/README.md). Una modifica
soltanto documentale richiede le verifiche statiche pertinenti, non un nuovo
benchmark o la riesecuzione indiscriminata degli E2E infrastrutturali.

Riferimenti: [testing HTTP Angular](https://angular.dev/guide/http/testing),
[coverage Angular](https://angular.dev/guide/testing/code-coverage),
[test isolati e comportamento osservabile](https://playwright.dev/docs/best-practices).
