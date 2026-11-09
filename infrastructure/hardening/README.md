# Verifica container

Le immagini di build e runtime sono fissate per versione e digest. I servizi
infrastrutturali e le basi applicative usano i repository dei rispettivi fornitori,
elencati in [policy.py](policy.py); non sono ammessi fork o ricostruzioni locali
delle immagini infrastrutturali. Le immagini applicative sono costruite dai
Dockerfile del progetto su queste basi. Il Compose
locale limita memoria e CPU per ogni servizio; le applicazioni Java destinano
al massimo il 75% della memoria del container all’heap. Restano necessari margini
per memoria nativa, thread e buffer. I limiti non sono una garanzia di capacità:
carichi diversi richiedono nuove misurazioni.

| Servizio | Memoria | CPU massime |
|---|---:|---:|
| PostgreSQL | 512 MiB | 1 |
| Flyway, kafka-init | 512 MiB ciascuno | 1 ciascuno |
| Redis | 128 MiB | 0,5 |
| Kafka | 1 GiB | 2 |
| API, gateway, processor | 512 MiB ciascuno | 2 ciascuno |
| Simulatore | 256 MiB | 1 |
| Frontend | 128 MiB | 0,5 |
| Prometheus, Grafana | 512 MiB ciascuno | 1 ciascuno |

I tetti di memoria sommano 5,5 GiB, inclusi i job temporanei. Docker richiede
anche memoria per sistema operativo e build. Le CPU sono tetti per container,
non core riservati. Aggiornare `.env` dalle versioni in `.env.example` richiede
una scelta esplicita: la verifica usa l’esempio e non modifica la configurazione
locale, i container attivi o i volumi esistenti.

Con Python 3.9+, Docker Compose e [Trivy](https://trivy.dev/docs/latest/getting-started/installation/)
installato (verificato con 0.76.2), dalla radice:

```sh
python3 -m unittest discover -s infrastructure/hardening -p 'test_*.py'
python3 infrastructure/hardening/verify.py --output tmp/container-check
```

Per un eseguibile fuori da `PATH`, aggiungere `--scanner /percorso/trivy`.
Usare una directory di output nuova. Occorrono rete per immagini/database CVE e
risorse per uno stack aggiuntivo. Le cache Docker e dello scanner sono riutilizzate.

La verifica copia i sorgenti tracciati e i nuovi file non ignorati in uno snapshot,
escludendo configurazione privata e artefatti. Costruisce e avvia un progetto
isolato con porte loopback dinamiche e volumi nuovi, mantenendo i limiti originali.
Controlla UID effettivi di PID 1 e job, limiti applicati dal daemon, dimensioni e
layer; esegue il flusso REST/TCP/Kafka/SQL/Redis/alert, proxy frontend e monitoring.
Le immagini applicative contengono solo gli artefatti runtime dei build multistage.

Trivy scansiona le immagini runtime esatte, per image ID, includendo pacchetti OS
e dipendenze applicative. Per l’immagine Prometheus priva di pacchetti OS,
controlla entrambi i binari Go. Qualsiasi High/Critical fa fallire il gate, anche senza
fix disponibile. Errori dello scanner e report mancanti/incompleti falliscono;
non sono previste esclusioni automatiche. Mantenere un’immagine ufficiale non
equivale ad accettarne le CVE residue. Per ogni rilievo conservare immagine/digest,
pacchetto, advisory, contesto e azione di riesame. Rivalutare a ogni aggiornamento
upstream o cambio di configurazione; eventuali eccezioni richiedono evidenze,
scadenza e accettazione esplicita, senza nascondere il report originale.
Una segnalazione non prova da sola la
sfruttabilità, e zero segnalazioni non garantisce assenza di vulnerabilità.
Le soglie di dimensione e layer sono definite in [policy.py](policy.py).
Spring Boot gestisce le dipendenze del framework. La proprietà `kafka.version`
nel POM allinea l’intera famiglia del client al broker e include i codec di
compressione corretti, secondo il [meccanismo Spring](https://docs.spring.io/spring-kafka/reference/appendix/override-boot-dependencies.html).

La valutazione del rischio usa anche esposizione, funzionalità attive, input e
segnalazioni di sfruttamento note. Il completamento dei controlli di hardening
locale e l’esito del gate CVE sono registrati separatamente: un risultato negativo
dello scanner resta visibile anche se la baseline funzionale è verificata.
Prima di un uso pubblico o con dati sensibili serve una nuova valutazione;
la baseline Compose è destinata allo sviluppo locale.

Le immagini degli stage di build richiedono inoltre una scansione separata: non
sono incluse nel risultato del gate runtime.

`images.json` registra identità, UID, dimensioni, layer e limiti. I report `scan-*`
e `scan-summary.json` conservano conteggi, ora e metadati dello scanner/database.
`checks.json` registra i controlli superati, `failure.json` l’errore;
`summary.json` viene creato solo dopo successo e pulizia delle risorse isolate.
Il fingerprint dei sorgenti distingue le modifiche locali dal commit di partenza.
La verifica HTTP non sostituisce le prove visuali o con screen reader.
