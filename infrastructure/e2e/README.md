# E2E backend FleetPulse — FP-050 / FP-051

Accettazione di [E2E-001](../../docs/12_STRATEGIA_DI_TEST.md#e2e-001--flusso-nominale):
registrazione REST → TCP → ACK → Kafka → PostgreSQL → Redis → Fleet API.

## Esecuzione

Dalla radice del repository, con Python 3.9+, Docker e Docker Compose attivi:

```bash
# Solo se manca: preservare la configurazione locale esistente.
test -f .env || cp .env.example .env
python3 infrastructure/e2e/verify_nominal.py --output tmp/fp050-nominale
```

Usare una directory output nuova. Lo script risolve la configurazione Compose,
compila le immagini backend e avvia PostgreSQL, Flyway, Kafka/topic, Redis e i tre
servizi in un progetto `fp050-<id>` dedicato. Riusa l'harness di isolamento FP-047:
porte loopback dinamiche, volumi dedicati, nessuna risorsa esterna o container
utente modificato. Simulator, frontend, Grafana e server Prometheus non vengono
avviati; le metriche vengono lette direttamente dagli endpoint applicativi.
Il test usa i default di progetto TTL Redis 5m e freshness 1m, attualmente non
sovrascritti nell'environment del Compose; le soglie alert sono lette dalla
configurazione risolta per scegliere valori nominali.

Il primo avvio può richiedere download/build. Attese di disponibilità e comandi
hanno deadline; il test non necessita porte host fisse. Non usare l'output come
benchmark o misura del requisito p95<2s, coperto da FP-047.

## Cosa viene verificato

1. Readiness dei backend prima del traffico.
2. Registrazione HTTP 201, Location, UUID, stato ACTIVE, dati e GET veicolo.
3. Chiave Redis del veicolo inizialmente assente.
4. Due frame TCP su una sola socket, ACK ACCEPTED/versione/messageId correlati.
5. Per ogni messaggio: singolo record raw, key vehicleId, versione evento,
   identificativi, observedAt, receivedAt gateway e valori telemetrici.
6. Singolo sample SQL con dati corrispondenti; precisione PostgreSQL a microsecondi
   considerata per receivedAt. processed_at non viene interpretato come commit.
7. Projection completa in Redis e TTL positivo entro 300s **prima** della prima
   GET State API per ciascun aggiornamento.
8. State API HTTP 200, dieci campi corretti, freshness secondo observedAt;
   delta cache hits +1 e fallback invariato, senza altri client API nello stack.
9. Riconciliazione: due sample, due record raw, nessun alert/rejected/DLT, lag zero.
10. Cleanup di container, rete e volumi del solo progetto, anche in caso di errore.

La lettura diretta Redis prima della GET impedisce che il repair API nasconda una
projection processor mancata. I due eventi hanno valori e tempi distinguibili;
il secondo prova che lo stato venga aggiornato, oltre alla creazione iniziale.
L'osservatore Kafka usa partizione/offset espliciti e auto-commit disabilitato,
senza usare il group del processor. Le query diagnostiche SQL sono read-only.
Non vengono inserite fixture direttamente nel database né usati mock applicativi.

## Risultati e diagnosi

Exit 0 indica successo. Nell'output:

- `checks.json`: verifiche completate con dati sintetici correlati;
- `summary.json`: esito nominale, 13 verifiche, due messaggi e progetto;
- `failure.json`: ultimo errore classificato, se la verifica fallisce;
- `cleanup.json`: risorse residue dopo il cleanup (liste vuote attese).

Il file Compose risolto transitorio contiene credenziali: è creato con permessi
0600 e rimosso alla fine. Non pubblicare configurazioni private. Le evidenze
riportano soltanto i dati sintetici del test; le immagini/cache di build restano
riutilizzabili dopo la rimozione dello stack.

In caso di errore leggere l'ultimo check completato e failure/cleanup; non
trasformare una mancata verifica in successo aumentando indiscriminatamente le
attese. Per la diagnosi dello stack normale usare il
[runbook operativo](../operations/README.md).

## Test del verificatore

```bash
python3 -m unittest discover -s infrastructure/e2e -p 'test_*.py' -v
```

Le regressioni controllano che dati errati, stato precedente, veicolo diverso e
campi tecnici estranei non vengano accettati e che i timestamp equivalenti siano
confrontati come istanti. Alert/replay appartengono a FP-051 e i guasti a FP-052;
questa prova mantiene il flusso nominale senza restart o interruzioni dei servizi.

## Alert e replay — FP-051

```bash
python3 infrastructure/e2e/verify_alerts.py --output tmp/fp051-alert-replay
```

Stessi prerequisiti/isolamento del nominale, progetto dedicato `fp051-<id>` e
directory output nuova. Lo scenario registra un veicolo, invia una temperatura
oltre la soglia configurata e mantiene batteria/odometro nominali per ottenere
esattamente un `ENGINE_TEMPERATURE_HIGH`, severity `HIGH` e stato `OPEN`.
Verifica il flusso TCP/Kafka/SQL/Redis/State API riusando FP-050, poi controlla
l'alert SQL, le collection REST scoped/globale e il dettaglio.

Ripubblica due volte lo **stesso frame** con lo stesso messageId: prima con alert
OPEN, poi dopo un PATCH REST a ACKNOWLEDGED. Ciascun replay deve essere accettato
e pubblicato su raw; il test attende incremento del counter duplicati e lag zero
prima di verificare che sample/alert completi siano invariati. Non basta che il
conteggio sia ancora uno subito dopo ACK: il processor potrebbe non avere ancora
consumato il replay. La seconda prova verifica che il lavoro dell'operatore non
venga perso: stesso alert ID, stato ACKNOWLEDGED e acknowledgedAt conservato.

Esito atteso: tre raw, un sample, un alert, due duplicati, zero rejected/DLT,
lag zero. checks/summary/failure/cleanup hanno lo stesso ruolo del nominale.
La suite `test_*.py` comprende anche le regressioni su sourceMessageId, sostituzione
dell'alert a conteggio invariato, riapertura, duplicazione e cleanup su failure.

Questa è una ripubblicazione TCP, non un reset offset o recupero DLT. I vincoli
transazionali e i test di crash precedenti completano la copertura: questo E2E
non certifica da solo ogni interleaving concorrente, tutti i tipi di alert,
le transizioni CLOSED o i failure scenarios FP-052. Nessun servizio Java modificato.
