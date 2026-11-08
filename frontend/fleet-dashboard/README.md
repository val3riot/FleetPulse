# Fleet Dashboard

Angular standalone/zoneless, TypeScript strict, CSS e Vitest.

## Prerequisiti e avvio

Node 22.23.2 (`.nvmrc`), npm 10+; versioni compatibili in `package.json`.

```bash
cd frontend/fleet-dashboard
nvm use
npm ci
npm start
```

UI: `http://127.0.0.1:4200`. Il proxy `proxy.conf.json` inoltra `/api` a
`http://localhost:8080`; aggiornare il target se cambia la porta Fleet API.
Un'origine API differente richiede CORS. Il proxy vale solo per sviluppo.

`API_CONFIG` configura base `/api/v1`, timeout 10s e un retry GET dopo 500ms.
`POLLING_CONFIG` configura 5000ms di attesa dopo risposta; valore intero positivo
entro 2147483647. I token possono essere sostituiti nei provider di `app.config.ts`.

## Comandi di verifica

```bash
npm run verify            # gate completo con coverage e controllo produzione
npm run test:ci           # test Angular senza coverage e senza backend
npm run test:coverage     # report HTML, JSON summary e LCOV
npm run test:fixtures     # test HTTP della fixture locale
npm run test:tooling      # test del controllo artefatti
npm run lint             # TypeScript e template Angular
npm run check:contrast   # contrasto sui token CSS
npm run format:check      # verifica formato
npm run format            # applica formato
npm run audit             # audit dipendenze, richiede accesso al registry
```

Report: `coverage/fleet-dashboard/index.html` e `coverage-summary.json`.
I file generati non sono versionati.

## Fixture UI isolata

In due terminali:

```bash
npm run mock:ui -- --target=dashboard --sequence=success
npm run start:ui-check
```

UI `http://127.0.0.1:4201`, mock `127.0.0.1:4302`, proxy `proxy.ui-check.json`.
Dati soltanto in memoria, nessun fallback o inoltro al backend reale.

`npm run mock:ui -- --help` elenca endpoint e scenari. `--sequence` accetta passi
separati da virgole e ripete l'ultimo; ogni tentativo HTTP al target, inclusi i
retry, consuma un passo. `--delay-ms` regola lo scenario lento; i timeout simulati
trattengono la risposta per 30s. Riavviare il mock azzera dati e sequenza; ricaricare
la UI non li azzera. Se si cambia `--port`, aggiornare il target del proxy.

La diagnostica GET separata usa `npm run mock:api` (porta 4301) e
`npm run start:api-check` (`/dev/api` su 4200). Non serve per le pagine operative.
Evitare conflitti con altri server sulla stessa porta; Ctrl+C arresta i processi.

## Build e distribuzione

```bash
npm run build             # distribuzione produzione
npm run check:production  # controlla la build già prodotta
npm run watch             # build sviluppo continua
```

Distribuire soltanto `dist/fleet-dashboard/browser/`. `browser-stats.json` resta
nella directory superiore per il controllo degli artefatti; non pubblicarlo.
La build applica budget, esclude diagnostica e non include fixture/test.

Il Dockerfile multi-stage esegue npm ci, build e controllo produzione; il runtime
Nginx non-root contiene soltanto gli asset. Fallback SPA e proxy API sono inclusi;
il proxy Angular di sviluppo non viene distribuito.

Dalla radice del repository, con il backend già attivo:

```bash
docker compose up --build -d --no-deps fleet-dashboard
```

Aprire `http://127.0.0.1:8083`. `FLEET_DASHBOARD_PORT` cambia la porta host;
`FLEET_API_UPSTREAM` seleziona l'origin HTTP del backend nella rete Docker
(default `http://fleet-api:8080`), senza path o credenziali. Il client mantiene
`/api/v1`: cambiare upstream richiede ricreazione del container, non rebuild.
Non usare localhost per indicare un altro container.

Healthcheck su `/healthz` con verifica del file index; non dipende dalla Fleet API.
Filesystem read-only e tmpfs `/tmp` sono configurati nel Compose.

Dalla directory frontend:

```bash
npm run test:container  # build e 13 verifiche HTTP Docker isolate con cleanup
```

Il comando richiede Docker e accesso al registry per le immagini fissate. Non usa
lo stack/DB reale; usa porte dinamiche e una rete propria. `-- --image=TAG`
verifica un'immagine già costruita senza eliminarla; senza opzione costruisce ed
elimina un tag di prova. Il test container resta separato dal gate senza Docker.

Dettagli tecnici: [architettura](../../docs/16_FRONTEND.md),
[test](../../docs/12_STRATEGIA_DI_TEST.md), [accessibilità](../../docs/17_ACCESSIBILITA_FRONTEND.md).
