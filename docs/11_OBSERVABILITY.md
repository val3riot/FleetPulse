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
`max` può essere disponibile secondo registry. FP-042 verificherà lo scrape
Prometheus; FP-041 verifica il formato esportato dall'endpoint reale.

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

### Verifica dello scrape — FP-042

Prometheus raccoglie i tre servizi ogni **15 secondi**, con timeout **5 secondi**,
su `/actuator/prometheus` nella rete Compose. `up=1` indica uno scrape riuscito:
non dimostra readiness né disponibilità di PostgreSQL, Kafka o Redis.
La pagina `/targets` e l'API `/api/v1/targets` mostrano stato e ultimo errore.

La verifica ripetibile richiede Python 3, senza pacchetti aggiuntivi:

```bash
docker compose up -d fleet-api telemetry-gateway telemetry-processor prometheus
docker compose exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
python3 infrastructure/prometheus/verify.py --report tmp/fp042-prometheus-results.json
```

Il comando verifica via API del server, non direttamente via Actuator: target
attesi, URL, intervallo/timeout, `up`, memoria/thread JVM e una metrica custom
per servizio. Accetta metriche presenti a zero: non simula traffico e non prova
throughput o latenza end-to-end. Serie assenti o non finite non passano.

Per verificare anche l'interruzione reale dei tre target:

```bash
python3 infrastructure/prometheus/verify.py --exercise-recovery \
  --report tmp/fp042-prometheus-recovery.json
```

L'opzione arresta e riavvia ogni applicazione in sequenza, attende `up=0` e
target down con errore, poi `up=1` e metriche nuovamente disponibili. Tutti
i target devono essere up prima di qualsiasi arresto; il servizio viene
riavviato in `finally` anche se la verifica down fallisce o viene interrotta.
Non terminare forzatamente il processo: in tal caso il cleanup non è garantito.
Il report JSON contiene esiti, timestamp dei campioni e tempi osservati, anche
su failure; exit code 0 significa successo, 1 failure.

Ogni controllo ha una deadline di 90 secondi (`--timeout`), con polling ogni
2 secondi e timeout HTTP 5 secondi. La finestra include almeno due intervalli
di scrape più margine di avvio; non è un SLO applicativo. `--url` supporta
una porta Prometheus locale diversa da quella predefinita.

Query manuali utili:

```promql
up{job=~"fleet-api|telemetry-gateway|telemetry-processor"}
jvm_memory_used_bytes{job="telemetry-processor"}
fleetpulse_gateway_connections_active{job="telemetry-gateway"}
fleetpulse_processor_events_total{job="telemetry-processor"}
fleetpulse_api_cache_hits_total{job="fleet-api"}
```

Contratti del server: [Prometheus HTTP API](https://prometheus.io/docs/prometheus/latest/querying/api/)
e [configurazione scrape](https://prometheus.io/docs/prometheus/latest/configuration/configuration/).

#### Criteri di chiusura FP-042

| Requisito del backlog | Verifica ripetibile |
|---|---|
| Target applicativi up | Tre target attesi healthy, senza errore, e query `up=1` |
| Scrape interval documentato | 15s in configurazione e documentazione, verificato tramite targets API |
| Metriche JVM e custom interrogabili | Query memoria/thread JVM e una metrica custom per ciascun servizio |
| Target down visibile | Arresto reale dei tre servizi in sequenza, target down con errore e `up=0`, poi recovery |

Validazione locale del 2026-10-08: `promtool` riuscito e 27 controlli del
verificatore finale passati con `--exercise-recovery`. Verificato anche il
caso endpoint irraggiungibile: exit 1 e report failure. Stack fermato al termine,
volumi conservati. Nessuna modifica Java: suite Maven non rieseguita.

## 4. Health

### Liveness

Indica che il processo applicativo è in esecuzione.

### Readiness

Indica che il servizio può svolgere la propria funzione primaria.

Esempi:

- Fleet API può essere ready con Redis down se PostgreSQL è disponibile;
- il processor non è ready a persistere se PostgreSQL è down;
- il gateway dipende dalla disponibilità del listener e dalla capacità di pubblicare.

## 5. Dashboard

Sezioni consigliate:

1. active connections;
2. frame ricevuti e rifiutati;
3. processing rate;
4. duplicati e failure;
5. processing latency percentiles;
6. cache hit/fallback rate;
7. JVM memory e thread;
8. dependency health.

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
