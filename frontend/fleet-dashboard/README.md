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

## Comandi

```bash
npm start             # server sviluppo su 127.0.0.1:4200
npm run build         # build produzione in dist/fleet-dashboard/browser
npm run watch         # build sviluppo continua
npm run verify        # lint, formato, test e build produzione
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
La fixture non contatta il backend reale. Per usare Fleet API reale, avviare
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

- [Architettura frontend e client API](../../docs/16_FRONTEND.md).
- [Contratto REST Fleet API](../../docs/09_SPECIFICA_API.md).
- [Deployment della piattaforma](../../docs/13_DEPLOYMENT.md).
