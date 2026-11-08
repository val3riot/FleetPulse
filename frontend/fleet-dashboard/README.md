# Fleet Dashboard

Client Angular di FleetPulse, integrato con Fleet API tramite REST HTTP/JSON.

## Prerequisiti

- Node 22.23.2, versione indicata in `.nvmrc`; le altre versioni supportate sono
  dichiarate in `package.json`.
- npm 10+; il package manager di riferimento è npm 10.9.8.

Stack: Angular 22.2.5, CLI/build 22.2.6, TypeScript strict, CSS, ESLint, Prettier
e Vitest. Il progetto usa componenti standalone e modalità zoneless.

## Installazione e avvio

```bash
cd frontend/fleet-dashboard
nvm use # se nvm è installato
npm ci
npm start
```

Aprire `http://127.0.0.1:4200`. Il frontend può partire senza backend;
le letture dei dati richiedono Fleet API o la fixture locale.

## Configurazione API e proxy

Il client usa `/api/v1` come base URL. I default di base URL, retry e timeout
sono in `src/app/core/api/api-config.ts`, sostituibili tramite il provider
`API_CONFIG` in `app.config.ts`.

Il proxy `proxy.conf.json` inoltra `/api/**` a `http://localhost:8080`.
Se si modifica `FLEET_API_PORT`, aggiornare il target del proxy. Il proxy vale
solo per `ng serve`. Un'origine API diversa richiede una configurazione CORS
compatibile sul backend.

## Configurazione aggiornamento periodico

Dashboard e ultima telemetria usano `POLLING_CONFIG`, definita in
`src/app/core/polling/polling-config.ts`. Il default è 5000 ms di attesa dopo
ogni lettura conclusa. È possibile sostituire il token nei provider di
`app.config.ts`; intervalMs deve essere un intero da 1 a 2147483647.
La UI offre pausa, ripresa e aggiornamento manuale. Nuove letture automatiche
sono sospese quando la scheda è nascosta.

## Comandi

```bash
npm start             # server sviluppo su 127.0.0.1:4200
npm run build         # build produzione in dist/fleet-dashboard/browser
npm run watch         # build sviluppo continua
npm run verify        # lint, formato, contrasto, test e build produzione
npm run check:contrast # verifica contrasti della palette CSS
npm run lint          # ESLint TypeScript e template Angular
npm test              # Vitest in modalità watch
npm run test:ci        # singola esecuzione test, senza backend
npm run format:check  # verifica formattazione
npm run format        # applica formattazione
npm run audit         # controllo vulnerabilità, richiede accesso al registry
```

## Ambiente con API fittizia

Per usare la fixture locale, eseguire in due terminali:

```bash
npm run mock:api        # API fittizia su 127.0.0.1:4301
```

```bash
npm run start:api-check # Angular con proxy.api-check.json
```

La diagnostica è raggiungibile su `http://127.0.0.1:4200/dev/api` solo in sviluppo.
La fixture non contatta il backend reale e offre soltanto risposte dimostrative;
per le pagine operative usare Fleet API reale o l’ambiente UI dedicato sotto descritto. Per usare Fleet API reale, avviare
lo stack dal repository e usare `npm start` con il proxy standard.

Fermare prima un eventuale server Angular sulla porta 4200 oppure passare
`-- --port 4201` al comando di avvio. Arrestare i processi con Ctrl+C.

## Build e distribuzione

La build produzione applica i budget definiti in `angular.json` e produce file
statici in `dist/fleet-dashboard/browser`. Esclude le rotte e i componenti di
diagnostica. I nuovi componenti generati dal CLI usano CSS.

Il frontend non è ancora incluso in Compose e non dispone di un'immagine
container. Il server di distribuzione dovrà gestire il fallback delle rotte SPA
e l'inoltro delle richieste API; il proxy di sviluppo non è incluso nella build.

## Documentazione

- [Requisiti di accessibilità](../../docs/17_ACCESSIBILITA_FRONTEND.md).
- [Architettura frontend e client API](../../docs/16_FRONTEND.md).
- [Contratto REST Fleet API](../../docs/09_SPECIFICA_API.md).
- [Deployment della piattaforma](../../docs/13_DEPLOYMENT.md).

## Ambiente UI con dati in memoria

Il server di fixture UI è separato dalla diagnostica `/dev/api` e dal backend.
Non usa credenziali, database o inoltro verso Fleet API. Espone gli endpoint
necessari a navigazione, filtri e modifiche; i dati vengono azzerati riavviando
il processo. Il frontend usa gli stessi componenti e lo stesso client HTTP.

Da due terminali nella cartella frontend:

```bash
npm run mock:ui -- --target=dashboard --sequence=success
npm run start:ui-check
```

Aprire `http://127.0.0.1:4201`. Il proxy dedicato `proxy.ui-check.json` inoltra
soltanto verso la fixture su `127.0.0.1:4302`, senza fallback al backend reale.
Il normale `npm start` e la sua porta 4200 restano indipendenti.

```bash
npm run mock:ui -- --help
npm run test:fixtures  # test HTTP della fixture, istanze isolate su porte effimere
```

`--target` seleziona l'endpoint, `--sequence` una sequenza di risposte separata
da virgole; l'ultimo passo viene ripetuto. Ogni richiesta HTTP al target,
compresi i retry del client, consuma un passo; gli altri endpoint rispondono
normalmente. `--delay-ms` regola la risposta lenta (default 2000). I timeout
simulati trattengono la risposta per 30000 ms; i timeout/retry applicativi non
vengono modificati. La fixture stampa metodo, path e passo utilizzato.

Riavviare soltanto `mock:ui` per cambiare scenario o azzerare i dati; ricaricare
la pagina per iniziare una nuova prova. Arrestare i processi con Ctrl+C.
Se si cambia `--port`, aggiornare il target del proxy prima di avviare Angular.

`npm run verify` include anche `test:fixtures`. Script e dati della fixture
restano fuori dagli asset e dai moduli della build produzione.
