# Observability

## 1. Obiettivi

I segnali operativi devono permettere di rispondere a:

- I servizi sono vivi e pronti?
- I client si stanno collegando?
- I frame vengono rifiutati?
- Il processor tiene il passo?
- Esistono eventi duplicati?
- Il fallback Redis è attivo?
- Quanto dura la persistenza?
- Dove si è fermato un `messageId`?

## 2. Structured logging

FP-040 adotta JSON ECS sulla console in `fleet-api`, `telemetry-gateway`,
`telemetry-processor` e `vehicle-simulator`, in tutti i profili. Si usa il supporto
nativo di Spring Boot con SLF4J fluent key/value; non sono necessarie dipendenze
aggiuntive. Ogni record occupa una riga JSON.

| Campo | Significato |
|---|---|
| `@timestamp` | Timestamp UTC ECS |
| `log.level`, `log.logger` | Livello e logger ECS |
| `service.name` | Nome da `spring.application.name` |
| `message` | Descrizione leggibile |
| `event.action` | Nome stabile dell'evento applicativo |
| `messageId` | Identificativo del messaggio di telemetria |
| `vehicleId` | Identificativo del veicolo, quando disponibile |
| `requestId` | Correlazione della richiesta HTTP |
| `connectionId` | Correlazione della connessione TCP nel worker |
| `sequenceNumber` | Sequenza della telemetria |
| `topic`, `partition`, `offset` | Posizione del record Kafka |
| `durationMs` | Durata numerica in millisecondi |
| `errorType`, `errorCode` | Classificazione tecnica o applicativa |

I campi ECS di base sono oggetti JSON (`log`, `service`, `ecs`); `event.action`
è passato come chiave puntata dal builder SLF4J e serializzato nell’oggetto `event`. I campi applicativi sono aggiunti
solo quando noti. `messageId` e `requestId` sono distinti; non si introduce
tracing distribuito né si modificano i contratti JSON REST, TCP o Kafka.

Livelli: `INFO` per esiti principali, `DEBUG` per dettagli, `WARN` per
situazioni degradate, `ERROR` per failure terminali/inattese. Rimangono i limiti
di frequenza dei warning cache/projection e tutti i contatori esistenti.

Non loggare payload completi, coordinate/valori di telemetria, credenziali,
request body, header arbitrari o messaggi/stacktrace di eccezioni applicative
che possono contenere questi dati. Le failure riportano la classe dell'errore.
L'aumento dei livelli delle librerie richiede una verifica separata dei dati
emessi; FP-040 non abilita DEBUG/TRACE delle librerie.

### Correlazione HTTP

`fleet-api` accetta e restituisce `X-Request-ID` su tutte le richieste HTTP,
comprese le risposte di errore. Un unico UUID canonico valido è riutilizzato
(normalizzato in minuscolo); header assente, invalido o ripetuto produce un nuovo
UUID, senza respingere la richiesta. L'identificativo non è una credenziale.

Il filtro salva il valore come attributo della richiesta e nel MDC durante il
passaggio nella filter chain; il contesto precedente viene ripristinato anche
in caso di eccezione. Dispatch async/error riutilizzano l'attributo. Non si
propaga automaticamente il MDC a task asincroni creati dall'applicazione.

`http.request.completed` registra metodo, stato e durata, senza query string o
body; una failure che esce dalla chain registra `http.request.failed` con stato
500. Per async la durata è quella del singolo dispatch, non end-to-end.

### Eventi di telemetria

Gli eventi consentono di seguire decodifica, ricezione, pubblicazione confermata
(`telemetry.publication.confirmed`), consumo Kafka, persistenza/duplicato,
rifiuto di dominio, aggiornamento della projection e retry/dead-letter.
Le failure Kafka aggiungono gli identificativi solo se il record contiene un
`TelemetryEvent` decodificato: per payload illeggibile si usano topic/partition/offset.
Il log di persistenza include il numero di candidati alert dell'aggregato.

Per sviluppo locale è disponibile un override esplicito al testo:
`FLEETPULSE_LOG_FORMAT=''`. Il profilo `local` mantiene ECS per default.
Vedi [ADR-013](adr/ADR-013-STRUCTURED-LOGGING.md).

## 3. Metriche — contratto FP-041

Questo catalogo è normativo. I nomi Micrometer usano punti; Prometheus converte
in underscore, aggiunge `_total` ai Counter e `_seconds` ai Timer. I Counter
contano eventi/tentativi; le Gauge descrivono lo stato corrente; i Timer usano
un clock monotono locale. Non ricavare durate da `processedAt - receivedAt`.

### Gateway

| Nome Micrometer | Tipo | Trigger / significato | Label |
|---|---|---|---|
| `fleetpulse.gateway.connections.active` | Gauge | Socket attualmente nel set dei client | Nessuna |
| `fleetpulse.gateway.connections.accepted` | Counter | Dispatch accettato dall'executor | Nessuna |
| `fleetpulse.gateway.connections.rejected` | Counter | Dispatch rifiutato dall'executor | Nessuna |
| `fleetpulse.gateway.tcp.connections.capacity.rejected` | Counter | Connessione oltre maxConnections | Nessuna |
| `fleetpulse.gateway.connections.timeouts` | Counter | Read timeout del client | Nessuna |
| `fleetpulse.gateway.connections.failures` | Counter | Configurazione/trasporto/handler fallito | Nessuna |
| `fleetpulse.gateway.frames.received` | Counter | Frame decodificato e validato | Nessuna |
| `fleetpulse.gateway.frames.rejected` | Counter | Errore framing/decodifica/validazione, una volta | `reason` finita |
| `fleetpulse.gateway.publish.failures` | Counter | Pubblicazione Kafka non confermata | Nessuna |
| `fleetpulse.gateway.publish.latency` | Timer | Chiamata publish → conferma, errore, interruzione o timeout | `outcome=confirmed,failed` |
| `fleetpulse.gateway.ack.latency` | Timer | Preparazione ACK/NACK nel handler | Nessuna |

`reason` ammette soltanto `invalid_length`, `too_large`, `truncated`, `malformed`,
`invalid`, `unsupported_version`. Un frame oltre il limite conta `too_large`,
non anche `invalid_length`. EOF prima di un nuovo frame, read timeout, errore di
trasporto e failure Kafka non sono rifiuti di validazione. Header/payload
troncato conta `truncated`; durante shutdown non si attribuiscono rifiuti a
socket chiusi dal server. Un rifiuto protocollo può contare anche una connection
failure: le due metriche descrivono fenomeni diversi e non vanno sommate.

Il timer ACK conserva il nome esistente, ma esclude lettura/decodifica del frame
e scrittura dell'ACK sul socket. Non misura il round trip visto dal simulatore.
Il timer publish esclude log e costruzione dell'ACK dopo la conferma.

### Processor

| Nome Micrometer | Tipo | Trigger / significato | Label |
|---|---|---|---|
| `fleetpulse.processor.events` | Counter | Tentativo di gestione di un TelemetryEvent decodificato | Nessuna |
| `fleetpulse.processor.persisted` | Counter | Ritorno riuscito del writer dopo commit aggregato | Nessuna |
| `fleetpulse.processor.duplicates` | Counter | Replay ignorato secondo classifier idempotenza | Nessuna |
| `fleetpulse.processor.failures` | Counter | failedDelivery Kafka, include retry | Nessuna |
| `fleetpulse.processor.failures.terminal` | Counter | Recovery terminale completato | Nessuna |
| `fleetpulse.processor.dead.letter` | Counter | Recovery con pubblicazione dead-letter riuscita | Nessuna |
| `fleetpulse.processor.rejections` | Counter | Veicolo rifiutato e pubblicazione rejected riuscita | `reason=UNKNOWN_VEHICLE,VEHICLE_DISABLED` |
| `fleetpulse.processing.latency` | Timer | Ingresso handler → esito aggregato, prima di Redis | `outcome=persisted,duplicate,rejected,failed` |
| `fleetpulse.pipeline.persistence.latency` | Timer | `TelemetryEvent.receivedAt` gateway → ritorno riuscito del writer dopo commit | Nessuna |
| `fleetpulse.pipeline.persistence.clock.invalid` | Counter | Durata gateway → commit negativa: orologi non coerenti, campione escluso | Nessuna |
| `fleetpulse.processor.projection.latency` | Timer | Tentativo update Redis post-commit | `outcome=completed,failed` |
| `fleetpulse.telemetry.latest_state.updates` | Counter | Esito update Redis | `outcome=updated,skipped,failed` |
| `fleetpulse.redis.update.failures` | Counter | Ogni update Redis fallito | Nessuna |

Ogni tentativo decodificato incrementa events e un solo outcome del timer. Il
successo viene misurato dopo il ritorno del proxy transazionale del writer;
il lavoro della projection è escluso. Il timer comprende validazione veicolo,
valutazione alert, persistenza e log del percorso aggregato. Duplicate/rejected
hanno durate proprie. I fallimenti Redis non annullano una persistenza riuscita.
`completed` sulla projection include sia aggiornamento sia skip; il contatore
updates distingue i due esiti. I warning limitati non limitano le metriche.

Esempio: tentativo DB fallito, retry riuscito, replay duplicato → events=3,
persisted=1, duplicates=1; timer failed/persisted/duplicate ciascuno count=1.
Un payload Kafka non decodificabile non entra nell'handler: è osservato da
failure/recovery Kafka, non dal contatore events. Non dedurre nuovi eventi
persistiti dal numero di record consegnati al listener o dagli offset.

Questa latenza è lavoro locale del processor, non gateway→commit end-to-end:
l'attesa in Kafka e il requisito RNF-003/FP-047 richiedono una misura distinta.
`fleetpulse.pipeline.persistence.latency` soddisfa questo confine: parte dal
mapping del frame valido nel gateway, prima della pubblicazione Kafka, e termina
subito dopo il ritorno del proxy transazionale del writer, senza transazione
esterna nell'orchestratore. Include coda e retry precedenti alla prima persistenza;
esclude Redis, duplicati ignorati e tentativi falliti. Non parte dall'ACK TCP.
Richiede orologi sincronizzati fra gateway e processor. Durate negative sono
escluse e contate, senza trasformarle in zero. `processed_at` viene assegnato
prima della transazione e non rappresenta l'istante di commit.

Il log `telemetry.event.persisted` aggiunge `pipeline.persistence.completedAt`,
`pipeline.persistence.latency.ms` numerico e `pipeline.persistence.clock.valid`.
Con clock invalido la durata è nulla. I campi sono correlabili per `messageId`,
senza label per messaggio, veicolo o run. Il percentile esatto di una prova usa
queste durate; il percentile dai bucket Prometheus resta una stima. Il timer
dedicato segue gli stessi override di distribuzione, incluso il bucket a 2 s
di default; la dashboard processor esistente continua a mostrare il timer locale.
Contratti domain/projection: [ADR-006](adr/ADR-006-AT-LEAST-ONCE-E-IDEMPOTENCY.md),
[ADR-007](adr/ADR-007-VALIDAZIONE-VEICOLO.md),
[ADR-009](adr/ADR-009-LATEST-STATE-PROJECTION.md).

### Fleet API

| Nome Micrometer | Tipo | Trigger / significato | Label |
|---|---|---|---|
| `http.server.requests` | Timer standard Spring Boot | Durata request HTTP gestita dal server | Tag standard framework con route normalizzata |
| `fleetpulse.api.cache.hits` | Counter | Lettura cache riuscita con stato valido | Nessuna |
| `fleetpulse.api.cache.misses` | Counter | Chiave assente | Nessuna |
| `fleetpulse.api.cache.fallback` | Counter | Accesso al percorso PostgreSQL, anche senza sample/in errore | Nessuna |
| `fleetpulse.api.cache.failures` | Counter | Errore lettura o decodifica cache | Nessuna |
| `fleetpulse.api.cache.repair.failures` | Counter | Repair cache fallito | Nessuna |

Non introdurre `fleetpulse.api.request.latency`: duplicherebbe
`http.server.requests`. Conservare i contatori FP-031 senza tag dinamici e il
warning condiviso limitato a uno ogni 30 secondi. Contratto:
[ADR-010](adr/ADR-010-STATE-API-FALLBACK.md).

### Histogram e cardinalità

I cinque Timer del catalogo pubblicano histogram Prometheus con:

- minimo atteso 1 ms e massimo atteso 30 s, configurabili;
- bucket Micrometer nel range più soglie esplicite
  50/100/250/500 ms, 1/2/5/10/30 s;
- nessun percentile calcolato nel client, non aggregabile fra istanze.

Min/max controllano la distribuzione, non sono timeout né gate prestazionali.
Le soglie aggiunte sono bucket osservativi, non nuovi SLO approvati. Valori oltre
il range restano nel count/sum e nel bucket +Inf. Le proprietà sono
`METRICS_TIMER_MIN`, `METRICS_TIMER_MAX`, `METRICS_TIMER_BUCKETS` (docs/13).

Per un Counter come `fleetpulse.processor.events`, l'export è
`fleetpulse_processor_events_total`; per la Gauge connections.active è
`fleetpulse_gateway_connections_active`. Ogni Timer esporta almeno
`<nome>_seconds_count`, `_seconds_sum`, `_seconds_bucket` con la label `le`;
`max` può essere disponibile secondo registry. FP-042 verifica la raccolta
server e i target; FP-041 verifica il formato esportato dall'endpoint reale.

Esempio p95 di elaborazione riuscita, aggregato sulle istanze:

```promql
histogram_quantile(0.95,
  sum by (le) (rate(fleetpulse_processing_latency_seconds_bucket{outcome="persisted"}[5m])))
```

Non mediare p95 fra istanze. Con poco traffico il percentile può essere instabile;
nessun campione è no-data, non zero. I bucket moltiplicano le serie per ogni
combinazione di tag: abilitarli solo sui Timer del catalogo. Nessuna label
messageId/vehicleId/requestId/connectionId/offset, payload, error.message, codice
veicolo o URI con UUID/query. I nomi route HTTP provengono dal framework; gli
outcome/reason applicativi sono enumerati. Non aggiungere tag per topic o
partition in FP-041. I campi ad alta cardinalità rimangono nei log FP-040.

Fonti: [Spring Boot metrics](https://docs.spring.io/spring-boot/reference/actuator/metrics.html),
[Micrometer histogram](https://docs.micrometer.io/micrometer/reference/concepts/histogram-quantiles.html).

### Raccolta delle metriche

Prometheus raccoglie `fleet-api`, `telemetry-gateway` e `telemetry-processor`
ogni **15 secondi**, con timeout **5 secondi**, tramite `/actuator/prometheus`
nella rete Compose. I target usano il nome DNS del servizio e la porta 8080.
La configurazione è in `infrastructure/prometheus/prometheus.yml`.

La metrica `up` indica il successo dello scrape: 1 per una raccolta riuscita,
0 per una raccolta fallita. Non dimostra readiness né disponibilità di
PostgreSQL, Kafka o Redis. Una serie assente deve restare distinguibile
da una misura valida a zero.

Il contratto di osservabilità richiede metriche JVM e applicative interrogabili,
rilevamento del target indisponibile e ripresa della raccolta dopo il ripristino.
Le verifiche di raccolta sono distinte dai test dell'export Actuator.

## 4. Health

### Liveness

Indica che il processo applicativo è in esecuzione.

### Readiness

Indica che il servizio può svolgere la propria funzione primaria.

Esempi:

- Fleet API può essere ready con Redis down se PostgreSQL è disponibile;
- il processor non è ready a persistere se PostgreSQL è down;
- il gateway dipende dalla disponibilità del listener e dalla capacità di pubblicare.

## 5. Dashboard Grafana — FP-043

La dashboard **FleetPulse Overview**, UID `fleetpulse-overview`, è provisionata
automaticamente nella cartella **FleetPulse**. Contiene 18 pannelli e 25 query
Prometheus reali, suddivisi in target/JVM, gateway, processor e API/cache.
Definizione versionata in `infrastructure/grafana/dashboards/fleetpulse-overview.json`.

Il datasource ha UID `fleetpulse-prometheus`, URL interno
`http://prometheus:9090` e scrape interval dichiarato 15s. Dashboard Classic
JSON, refresh 15s, range iniziale 30m. Le query rate usano `$__rate_interval`:
almeno 1m con questo scrape interval, più ampio secondo passo/range Grafana.
Gauge visualizzate come valori, Counter come rate/s e p95 da bucket aggregati
con `le`, non da percentili client. L'HTTP include solo URI `/api/v1/.*`.

| Sezione | Contenuto |
|---|---|
| Target/JVM | Scrape UP/DOWN per servizio, memoria utilizzata e thread JVM |
| Gateway | Connessioni, frame/s, rifiuti per reason, failure publish/s, p95 publish confirmed |
| Processor | Tentativi/persistenze/duplicati, rifiuti dominio, failure/recovery Kafka distinte, esiti Redis, p95 persisted |
| API/cache | Richieste ed errori REST/s, hits/misses/fallback, failure lettura/repair, p95 REST |

UP indica successo dello scrape, non dependency health/readiness. Metriche
assenti rimangono NO DATA, percentili senza campioni non diventano zero.
Le curve storiche possono restare visibili durante un outage; il pannello
scrape mostra lo stato corrente. Non sommare contatori con semantiche
sovrapposte (tentativi/persistenze, retry/terminal/DLT, miss/fallback).
Nessun consumer lag o health di dipendenza senza una fonte verificata.

Il provider rilegge i JSON ogni 30s. La definizione versionata è la fonte
della configurazione: la dashboard provisionata non consente salvataggi dalla
UI. Datasource e provider vengono caricati all'avvio. Il datasource mantiene
l'UID stabile anche sulle installazioni precedenti, tramite ricreazione della
configurazione della connessione prima del provisioning.

## 6. Correlazione

Per un `messageId` devono essere individuabili:

1. accettazione nel gateway;
2. risultato della pubblicazione;
3. consumo;
4. eventuale rifiuto di dominio;
5. persistenza;
6. aggiornamento Redis;
7. eventuale alert o dead-letter.

## ADR di riferimento

- [ADR-005 — Redis come cache ricostruibile](adr/ADR-005-REDIS-CACHE-RICOSTRUIBILE.md)
- [ADR-006 — At-least-once con application idempotency](adr/ADR-006-AT-LEAST-ONCE-E-IDEMPOTENCY.md)
- [ADR-007 — Validazione del veicolo nel telemetry processor](adr/ADR-007-VALIDAZIONE-VEICOLO.md)

- [ADR-009 — Contratto e aggiornamento della latest-state projection](adr/ADR-009-LATEST-STATE-PROJECTION.md)

## Health, liveness e readiness — FP-048

I tre servizi espongono `/actuator/health`, `/actuator/health/liveness` e
`/actuator/health/readiness` sulla stessa porta HTTP applicativa. Le probe sono
abilitate esplicitamente; i dettagli e i componenti non sono pubblicati.
`UP` corrisponde a HTTP 200; `DOWN` e `OUT_OF_SERVICE` a HTTP 503. Queste risposte
sono payload Actuator, distinti dagli errori REST del dominio.

| Servizio | Gruppo liveness | Gruppo readiness |
| --- | --- | --- |
| Fleet API | `livenessState` | `readinessState,db` |
| Gateway | `livenessState` | `readinessState,tcp,kafka` |
| Processor | `livenessState` | `readinessState,db,kafka,consumer` |

Liveness osserva lo stato interno dell'applicazione e non interroga dipendenze
esterne. Readiness indica disponibilità del ruolo principale. Un guasto DB o
Kafka non rende il processo non vivo; una readiness negativa non blocca di per
sé richieste HTTP o consumo Kafka e non impedisce il recovery.

Redis è opzionale per API e processor: gli indicatori Redis automatici sono
disabilitati, così la cache indisponibile non rende DOWN neppure l'health
generale. Fallback, read repair e projection restano osservabili tramite le
metriche e i log già definiti; non si introduce un secondo endpoint Redis.
Con PostgreSQL fermo la readiness dell'intera API è negativa, anche se un hit
Redis può ancora servire la State API. Un miss continua invece a richiedere DB.
Il contratto applicativo di [ADR-010](adr/ADR-010-STATE-API-FALLBACK.md) resta valido.

`tcp` richiede un `TcpServerLifecycle` presente e in esecuzione: listener
disabilitato o terminato implica DOWN anche se il server HTTP è raggiungibile.
`consumer` richiede il container del listener `fleetpulse-raw-telemetry` presente
e avviato. Questo ID non sostituisce il consumer group configurato. Pause,
rebalance e assenza di partizioni assegnate non sono automaticamente guasti:
il controllo non misura lag, progresso o throughput del consumer.

`kafka` usa un Admin client gestito dal servizio, con la configurazione del
KafkaAdmin esistente, e legge metadati/leader dei topic. Gateway richiede raw;
processor richiede raw, rejected e dead-letter. Non pubblica eventi di prova
e non certifica permessi WRITE/FETCH o riuscita di ogni operazione futura.
Il client è chiuso allo shutdown. Le verifiche sono serializzate e il risultato
è riusato per un secondo dal completamento, limitando il costo delle probe;
i cambiamenti di stato possono quindi essere osservati con questo ritardo.

`KAFKA_HEALTH_TIMEOUT` ha default `1s`, intervallo consentito `1ms..2s`, e limita
la singola richiesta metadata e l'attesa del risultato. Eccezioni e indirizzi
interni non vengono aggiunti alla risposta health. Il datasource mantiene il
controllo DB standard: `DB_POOL_CONNECTION_TIMEOUT=1000` e
`DB_POOL_VALIDATION_TIMEOUT=500` sono millisecondi e limitano acquisizione e
validazione del pool; non sono timeout universali di tutte le query SQL.
API e processor usano timeout Redis di connessione/comando di default `500ms`,
configurabili tramite `SPRING_DATA_REDIS_CONNECT_TIMEOUT` e `SPRING_DATA_REDIS_TIMEOUT`.

Il budget verificato per le probe nei guasti stop/restart di riferimento è
inferiore a 5 s con i default. Non estendere questo risultato a ogni possibile
blackhole JDBC o a override dei timeout. La disponibilità cold start dipende
anche dall'inizializzazione JPA e dei topic: la readiness a runtime non promette
l'avvio completo con PostgreSQL indisponibile.
