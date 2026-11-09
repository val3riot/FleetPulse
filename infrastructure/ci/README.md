# Continuous integration

[Workflow](../../.github/workflows/ci.yml) su push a `main`, pull request verso
`main` e avvio manuale da GitHub Actions. Usa runner GitHub Ubuntu 24.04 separati;
non esegue deploy, pubblicazione di immagini o creazione di release.

| Job | Controlli |
|---|---|
| Backend | Temurin 21, wrapper Maven, `clean verify` completo: unit/integration test, compiler, convergenza dipendenze e formato. Docker disponibile per Testcontainers. |
| Frontend | Node da `.nvmrc`, `npm ci`, lint/formato/contrasto, coverage e test, build, controllo degli artefatti e audit npm. |
| Tooling and documentation | Test Python dei verificatori, coerenza link/configurazione e lint del workflow con actionlint. |
| Isolated end-to-end demo | Dopo i primi tre job: servizi reali su database nuovo, telemetria/alert e fermo/recupero del processor senza ritrasmissione. |
| Quality gate | Richiede successo di tutti i job precedenti; missing, skipped, cancelled e failure non sono successi. |

Le prove del job integration usano solo `.env.example`, porte loopback dinamiche
e risorse proprie; non richiedono secret applicativi. I runner sono effimeri.
Le esecuzioni superate da un nuovo commit sullo stesso riferimento sono annullate.
Il benchmark di carico, lo scan delle immagini e la matrice completa dei guasti
restano prove separate, non eseguite implicitamente a ogni PR.

## Cache e permessi

Cache dei soli download: repository Maven, cache npm e cache pip, con chiavi
legate ai rispettivi POM/wrapper, lockfile o requirements. Nessuna cache di
`target`, `node_modules`, `dist`, dati Docker o configurazioni private.
Le immagini Docker vengono costruite sul runner senza cache remota condivisa.

Actions dei repository ufficiali GitHub fissate a SHA completo, con versione nel
commento; actionlint scaricato dalla release upstream e verificato con SHA256.
Per aggiornarli verificare commit/tag e checksum alla fonte. Il token ha solo
`contents: read`; checkout non conserva credenziali. Si usa `pull_request`,
senza eseguire il codice delle PR tramite `pull_request_target`.

## Artefatti e diagnosi

Log, report Surefire, coverage, audit npm e risultati della demo sono conservati
per sette giorni, con commit e tentativo nel nome. Sono caricati anche dopo un
fallimento, salvo cancellazione dell’esecuzione. Il bundle frontend è caricato
solo quando il gate frontend è riuscito. Nessun upload generico del workspace,
di `.env`, configurazioni Compose risolte o directory di dipendenze.

I comandi con `tee` usano Bash con `pipefail`: salvare un log non nasconde l’exit
code del test. Per riprodurre i gate usare i comandi nei README di
[quality](../quality/README.md), [frontend](../../frontend/fleet-dashboard/README.md)
e [demo](../demo/README.md). I test del riepilogo:

```sh
python3 -m unittest discover -s infrastructure/ci -p 'test_*.py'
```

Per verificare YAML, espressioni e script shell usare actionlint (versione e
checksum nel workflow). In GitHub aprire l’esecuzione del commit, controllare
`Quality gate`, i singoli job e gli artefatti. Se si adottano regole di protezione
del branch, richiedere il check `Quality gate`; il workflow non modifica tali
impostazioni del repository.

Il riepilogo distingue il gate funzionale e l’audit npm dalla scansione CVE delle
immagini: una CI verde non attesta la risoluzione dei rischi upstream o l’idoneità
alla produzione. La validazione locale del workflow non sostituisce la prima
esecuzione effettiva sui runner GitHub.
