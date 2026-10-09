# Operations runbook — FleetPulse (FP-049)

Procedure per lo stack locale del repository. Contratti: [guasti](../../docs/10_AFFIDABILITA_E_GUASTI.md),
[observability](../../docs/11_OBSERVABILITY.md), [deployment](../../docs/13_DEPLOYMENT.md).
Non costituisce un piano di backup/restore o HA per produzione.

## Prerequisiti e scelta dell'ambiente

Eseguire dalla radice del repository con Docker Compose e Python 3.9+.
Conservare il proprio `.env`: copiarlo da `.env.example` solo se non esiste.
Annotare commit (`git rev-parse HEAD`), progetto, intervallo UTC, messageId e
vehicleId prima di intervenire. Credenziali e configurazioni risolte non vanno
incollate nei ticket. Gli esempi usano il progetto Compose predefinito;
per un altro progetto passare `--project NOME --file /percorso/compose.yaml`
all'helper, e gli stessi `-p NOME -f /percorso/compose.yaml` a ogni comando Compose.
Un override Compose va fornito come file risolto all'helper; il file può contenere
secret, quindi conservarlo con permessi restrittivi e rimuoverlo dopo l'uso.

L'helper `diagnose.py` esegue soltanto letture. Risolve topic e group da Compose,
usa le porte pubblicate correnti, PostgreSQL tramite le variabili del container
e Redis tramite `REDISCLI_AUTH`. Non richiede `source .env`, jq o client installati
sull'host. Un comando fallito termina con exit 1; un risultato vuoto non prova
assenza storica. Le probe mostrano gli HTTP status anche quando DOWN: valutare
il contenuto, non solo l'exit code dell'helper.

```bash
python3 infrastructure/operations/diagnose.py status
python3 infrastructure/operations/diagnose.py health
python3 infrastructure/operations/diagnose.py kafka
```

`status` include i job one-shot: Flyway e kafka-init conclusi con exit 0 sono
normali. Health deve riportare readiness/liveness UP dei tre backend nello stato
nominale. Redis indisponibile non rende il ruolo applicativo non ready; un cache
hit API può riuscire anche mentre PostgreSQL rende readiness DOWN.
Prometheus `up=1` dimostra uno scrape riuscito, non la disponibilità applicativa.

## Diagnosi di un messaggio

Sostituire gli UUID di esempio con quelli del caso. I comandi validano gli UUID.

```bash
python3 infrastructure/operations/diagnose.py logs 00000000-0000-0000-0000-000000000001 --since 30m --tail 2000
python3 infrastructure/operations/diagnose.py database 00000000-0000-0000-0000-000000000001
python3 infrastructure/operations/diagnose.py redis 00000000-0000-0000-0000-000000000002
```

1. Nei log cercare `telemetry.publication.confirmed`: l'ACK ACCEPTED certifica
   pubblicazione Kafka dopo validazione tecnica, non commit SQL o validità del
   veicolo. Il gateway non promette coordinate Kafka nel log di conferma.
2. Cercare `telemetry.event.persisted`, `duplicate.telemetry.aggregate.ignored`,
   `telemetry.event.rejected` e gli eventi di errore/retry del processor.
   Il filtro interpreta i campi ECS annidati (`event.action`). La finestra e il
   limite sono **per servizio**; se insufficienti ampliarli in modo mirato.
   In formato testuale l'helper conta i match non JSON senza esporli: usare
   `docker compose logs --no-color --no-log-prefix --since 30m --tail 2000 telemetry-gateway telemetry-processor`
   e cercare l'UUID localmente, proteggendo l'output che può contenere dati.
3. La query SQL usa una transazione read-only, timeout 5s e legge separatamente
   sample e alert. Un sample prova persistenza; zero alert è legittimo se nessuna
   regola scatta. `processed_at` non è il timestamp del commit.
4. Se il sample manca, controllare lag, rejected e DLT prima di parlare di perdita.
   Nei log del processor o negli eventi terminali recuperare topic, partition,
   offset. Il comando `kafka` mostra metadata, offset earliest/latest e group.
   Lag `-` significa offset non disponibile/non committed: non convertirlo
   automaticamente in zero. Retention e offset iniziale vanno considerati.
5. Leggere una finestra del topic con partizione e offset espliciti:

```bash
python3 infrastructure/operations/diagnose.py records raw --partition 0 --offset 0 --count 20
python3 infrastructure/operations/diagnose.py records rejected --partition 0 --offset 0 --count 20
python3 infrastructure/operations/diagnose.py records dlt --partition 0 --offset 0 --count 20
```

Usare un offset compreso fra earliest incluso e latest escluso; latest è la
posizione successiva all'ultimo record. Offset 0 è solo un esempio per topic
nuovi. Ispezionare le altre partizioni quando le coordinate non sono note.
Il consumer legge al massimo 100 record e attende al massimo 5s senza nuovi
record; il processo ha deadline 20s. Non utilizza il group applicativo e disabilita
auto-commit. Non modifica gli offset del processor. L'output contiene soltanto
identificativi/esiti, non payload completi; una finestra può non includere l'evento.

Rejected espone `messageId`, motivo e coordinate originali. DLT espone
`sourceTopic/sourcePartition/sourceOffset`, `attempts`, `errorCode`; messageId,
quando recuperabile, è in `originalPayload` e viene mostrato come
`originalMessageId`. Un payload malformato può non avere UUID: usare le coordinate,
non supporre che `originalKey` sia un messageId. Le coordinate sorgente sono
quelle del raw, non la posizione del record nel topic DLT/rejected.

6. In Redis leggere solo `vehicle:last:<vehicleId>`: PING, GET, TTL. TTL `-2`
   significa chiave assente, `-1` nessuna scadenza (da indagare rispetto al contratto).
   TTL non misura freshness: confrontare sequenza e tempi con il sample e la State
   API. La projection non contiene messageId e può già rappresentare un evento
   più recente. Le tre letture non sono uno snapshot atomico.

Concludere con una classificazione supportata: persistito, duplicato ignorato,
rifiutato, errore terminale, in attesa, projection degradata oppure evidenza
insufficiente. Assenza nei log/topic può dipendere da retention o finestra.
Lag zero significa che il group ha raggiunto la fine, non che tutti i messaggi
siano finiti nel database.

## Interventi e verifica del recupero

| Sintomo | Controlli | Intervento minimo | Criterio di recupero |
|---|---|---|---|
| NACK upstream / gateway non ready | Kafka healthy, raw presente, log gateway | Ripristinare Kafka/topic previsti | Gateway ready; nuovo frame accettato e sample persistito |
| Frame invalido / capacità esaurita | Protocollo, lunghezza, limiti connessioni | Correggere mittente o ridurre carico | Frame valido accettato; niente restart DB |
| Lag cresce / processor fermo | Stato container, readiness, log retry, DB e Kafka | Ripristinare dipendenza; avviare processor se fermo | Nuovi sample persistiti, lag drenato/stabile, rejected/DLT riconciliati |
| PostgreSQL indisponibile | `pg_isready`, health API/processor, errori SQL | Ripristinare DB preservando volume | Readiness UP e nuova persistenza; controllare messaggi già in DLT |
| API hit 200 ma readiness DOWN | PostgreSQL down, dato presente in cache | Ripristinare PostgreSQL | Hit e miss tornano servibili; readiness UP |
| Redis indisponibile / projection failure | PING, metriche failure, stato API vs SQL | Ripristinare Redis; leggere State API o inviare nuova telemetria | Stato coerente con SQL, chiave/TTL validi e nuovi update riusciti |
| Rejection dominio | Reason, veicolo esistente/ACTIVE | Correggere la causa applicativa | Nuovo evento legittimo persistito; precedente rifiuto spiegato |
| DLT | Error code, attempts, coordinate, log | Correggere causa; conservare riferimenti per recupero separato | Nuovi eventi processati; eventi terminali censiti, non dichiarati recuperati |
| HTTP risponde ma TCP non disponibile | Readiness gateway, log listener, connessione TCP | Ripristinare/restart gateway se listener terminato | Readiness UP e frame reale accettato |
| Grafana vuota | Range temporale, datasource, target Prometheus, query diretta | Ripristinare scrape/datasource; scegliere finestra con traffico | Target UP e query/dashboard restituiscono dati attesi |

Controllo PostgreSQL diretto senza password in argomenti:

```bash
docker compose exec -T postgres sh -ec 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Per Redis: nessun `KEYS *`, `FLUSHALL` o `FLUSHDB` nella diagnosi.
Il read repair avviene con `GET /api/v1/vehicles/<vehicleId>/state` sulla porta
Fleet API configurata; è una lettura applicativa che **può scrivere la cache**, a
differenza dell'helper. Confrontare `lastSequenceNumber` con PostgreSQL.
Il processor aggiorna la projection con un nuovo evento; il replay di un duplicato
persistito non ripete l'update Redis. Non esiste un rebuild batch implementato.
I test di recovery con endpoint stabile non garantiscono ogni cambio IP/DNS;
se Redis risulta raggiungibile ma il client continua a fallire, conservare log,
riavviare il servizio interessato e verificare read repair/nuova telemetria.

Non esiste replay automatico/manuale assistito della DLT. Ripristinare PostgreSQL
non ripubblica i messaggi terminali. Non usare reset offset come rimedio ordinario:
può rileggere un intervallo ampio e non garantisce riparazione della cache.
Una futura procedura di replay richiede perimetro, idempotenza, tracciamento e
verifica propri; non è un comando disponibile in questo runbook.

## Restart, aggiornamento e arresto

Esempi su processor; usare il servizio effettivamente interessato. Prima acquisire
stato/log; dopo usare `health`, `kafka` e una nuova telemetria controllata.

```bash
# Stessa immagine e stesse variabili: non compila Java né applica modifiche .env.
docker compose restart telemetry-processor
# Modifiche Java o Dockerfile: build e ricreazione del servizio.
docker compose up -d --build --no-deps telemetry-processor
# Modifiche environment/.env: ricreazione con nuova configurazione.
docker compose up -d --no-deps telemetry-processor
# Arresto e ripartenza mantenendo container e volumi.
docker compose stop telemetry-processor
docker compose start telemetry-processor
```

`--no-deps` presuppone dipendenze già disponibili e migrazioni compatibili;
per un aggiornamento che modifica lo schema eseguire il normale avvio orchestrato
con Flyway. Non modificare migration già applicate. Per rollback usare il commit/
immagine precedente solo se compatibile con lo schema, preservando i volumi.
Il restart processor con stesso group riprende dagli offset committed; un offset
non confermato può causare replay idempotente. Non serve un reset del group.

File montati: per `prometheus.yml` riavviare Prometheus; dashboard JSON Grafana
vengono rilette entro 30s; datasource/provider richiedono restart Grafana.
Modifiche a mount/env richiedono ricreazione con `up -d` del servizio interessato.
Container unhealthy non è automaticamente riavviato dalla restart policy.

Arresto ordinario dell'intero progetto:

```bash
docker compose down
# Ripartenza: riusa i volumi nominati del medesimo progetto.
docker compose up -d
```

`down` rimuove container/rete e conserva i volumi nominati PostgreSQL, Kafka e
Grafana. Redis ha persistenza disabilitata e perde la cache al riavvio; Prometheus
non ha un volume TSDB e può perdere la storia quando il container viene rimosso.
Il nome progetto deve restare lo stesso per riutilizzare i volumi.

Reset distruttivo **solo per un progetto locale sacrificabile identificato**:

```bash
docker compose -p PROGETTO_SACRIFICABILE -f /percorso/compose.yaml down --volumes --remove-orphans
```

Elimina anche storico PostgreSQL, Kafka/offset e configurazione persistita Grafana
di quel progetto. Non serve ad aggiornare dashboard o a recuperare un servizio.
Non usare prune globale. I verificatori creano progetti unici e rimuovono soltanto
le proprie risorse; non eseguire prove di guasto/reset sullo stack di lavoro.

## Verifiche ripetibili

```bash
python3 infrastructure/operations/verify.py --output tmp/fp049-verifica
python3 infrastructure/health/verify.py --output tmp/fp048-health-nuova-verifica
```

La prima prova in stack isolato i comandi diagnostici, sample+alert, duplicato,
rejection, DLT con payload non decodificabile, Redis down/read repair, restart,
ricreazione dopo modifica environment, conservazione dei volumi con down/up e
cleanup scoped. La seconda verifica la
matrice health con Redis/PostgreSQL/Kafka down e recovery. Richiedono Docker,
spazio per build, rete per immagini mancanti; gli output devono essere directory
nuove. Evidenze in `tmp`, mai secret nel report. Nessuna prova implica replay DLT.
