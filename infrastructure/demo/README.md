# Demo locale

Prerequisiti: Python 3.9+, Git e Docker con Compose, accesso ai registry e risorse
per uno stack aggiuntivo. Le immagini ufficiali e i limiti sono definiti nella
[configurazione container](../hardening/README.md). La demo verifica il flusso
funzionale; non esegue né sostituisce il gate vulnerabilità.

Dalla radice del repository, scegliere una directory di output nuova:

```sh
# Esecuzione automatica: verifica tutti i passaggi e rimuove lo stack.
python3 infrastructure/demo/run.py --output tmp/demo-automatica

# Presentazione con pause per osservare la dashboard.
python3 infrastructure/demo/run.py --output tmp/demo-interattiva --interactive
```

Lo script copia i sorgenti in uno snapshot, legge solo `.env.example` e costruisce
le immagini. Avvia uno stack completo con database vuoto, migrazioni automatiche,
volumi propri e porte dinamiche associate al loopback. Non riusa la `.env`, i dati
o i container del workspace; le cache di download/build restano riutilizzabili.

## Sequenza osservabile

| Pausa | Cosa verificare nella UI |
|---|---|
| Telemetria nominale | Veicolo attivo, velocità 60, temperatura sotto soglia, un campione storico, nessun alert. |
| Temperatura alta | Velocità 70, secondo campione e un alert temperatura correlato al veicolo. |
| Processor fermo | Valori precedenti conservati: Kafka ha accettato il terzo messaggio, ma lo storico ha ancora due campioni. |
| Recupero | Velocità 80, terzo campione, stesso alert conservato e coda Kafka svuotata senza ritrasmissione. |

A ogni pausa sono stampati i link a dashboard, veicolo, storico e alert, insieme
all’intervallo da impostare nello storico. Aprirli nel browser e consultare i dati;
non creare/disabilitare veicoli o generare traffico parallelo nella demo.
Premere Invio nel terminale per continuare. Dopo l’ultima pausa, Invio rimuove le
risorse: i link non sono più disponibili. Le pause prolungate possono rendere
lo stato stale secondo le normali regole di freshness.

Il guasto arresta soltanto il processor del progetto sacrificabile, dopo averne
controllato l’appartenenza. L’ACK dimostra accettazione dal gateway, distinta dalla
persistenza asincrona: offset del broker, consumer lag, stato e storico mostrano
il messaggio pendente. Il recupero usa lo stesso container/group e non ripubblica
il frame. Non vengono scritti dati direttamente nel database o usati mock API.

## Esiti e interruzioni

Exit 0 e `summary.json` indicano scenario e pulizia completati: tre messaggi,
tre campioni, sequenza finale 2, un alert e lag zero. `checks.json` registra i
passaggi; `session.json` contiene fase, identificativi, link e intervallo storico.
Dopo la pulizia riporta `resources: removed` e `linksActive: false`.

Ctrl+C, SIGTERM, fine dello stdin interattivo o un errore eseguono comunque la
pulizia del solo progetto di demo e non producono un riepilogo di successo.
`failure.json` descrive l’errore; un problema di pulizia è conservato in
`cleanup-failure.json`. In caso di SIGKILL o arresto del daemon può servire pulizia
manuale del progetto indicato nei risultati. Non usare `prune` o `down` sullo
stack abituale per risolvere una demo interrotta.

Test del verificatore:

```sh
python3 -m unittest discover -s infrastructure/demo -p 'test_*.py'
```

I test coprono correlazione dello stato, offset mancanti/duplicati, partizioni
omesse solo se vuote, isolamento del processor, pause e gestione degli errori.
Le risposte HTTP della SPA non certificano il rendering visuale o lo screen reader.
