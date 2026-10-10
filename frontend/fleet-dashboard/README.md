# Fleet Dashboard

Fleet Dashboard è il client web funzionale destinato agli operatori della
flotta. Consente di consultare lo stato operativo dei veicoli, registrare nuovi
veicoli, analizzare la telemetria e consultare gli alert.

## Responsabilità funzionali

- mostrare una panoramica sintetica della flotta;
- cercare e filtrare i veicoli;
- registrare un veicolo;
- mostrare dettaglio, stato corrente e storico telemetrico di un veicolo;
- cercare e filtrare gli alert;
- mostrare il dettaglio di un alert.

## Pagine previste

- Dashboard;
- elenco veicoli;
- registrazione veicolo;
- dettaglio veicolo;
- elenco alert;
- dettaglio alert.

## Integrazione

Fleet Dashboard comunica esclusivamente con `fleet-api` tramite REST HTTP/JSON.
Non accede direttamente a Telemetry Gateway, Telemetry Processor, Vehicle
Simulator, Kafka, PostgreSQL, Redis, Prometheus o Grafana.

Nel primo rilascio i dati vengono aggiornati tramite polling REST periodico. Un
intervallo iniziale indicativo di 3–5 secondi deve essere configurabile. WebSocket
e Server-Sent Events non fanno parte del primo rilascio.

Grafana rimane un'interfaccia tecnica separata, destinata all'observability della
piattaforma; non fa parte del frontend funzionale della flotta.

## Base tecnica — preparazione FP-060

Angular 22.2.2, standalone e zoneless, routing predisposto, TypeScript strict,
SCSS, HttpClient e Vitest. Il progetto contiene soltanto il componente radice
con una pagina neutra: le pagine funzionali e i servizi REST sono da implementare.
Non sono state introdotte librerie UI, gestione dello stato o container frontend.

### Requisiti e avvio

Node 22.23.2 (versione usata per la verifica, disponibile in `.nvmrc`) e npm 10+.
Le altre versioni Node supportate sono dichiarate in `package.json`.

```bash
cd frontend/fleet-dashboard
# Se usi nvm:
nvm use
npm ci
npm start
```

Aprire `http://127.0.0.1:4200`. Il frontend può partire anche senza backend.
Il proxy di sviluppo inoltra `/api/**` a `http://localhost:8080`, porta predefinita
di `fleet-api`. Se personalizzi `FLEET_API_PORT`, adegua `proxy.conf.json`.
Nei futuri servizi usare URL relativi, ad esempio `/api/v1/vehicles`.
Il proxy vale solo per `ng serve`; l'integrazione di produzione sarà definita
quando verrà aggiunto il deployment frontend.

### Comandi

```bash
npm run build         # build produzione in dist/fleet-dashboard/browser
npm run watch         # build sviluppo continua
npm test              # Vitest in modalità watch
npm run test:ci        # singola esecuzione test
npm run format:check  # verifica formattazione
npm run format        # applica formattazione
```

### Punti di partenza per i primi moduli

- `src/app/app.routes.ts`: aggiungere le rotte delle funzionalità.
- `src/app/app.config.ts`: configurazione condivisa e provider applicativi.
- `src/app/app.*`: componente radice, template e stile iniziali.
- `src/styles.scss`: stili globali.

I componenti generati dal CLI sono standalone e usano SCSS. Le funzionalità
possono essere organizzate per dominio sotto `src/app/`, man mano che vengono
implementate. La struttura dei moduli funzionali e la UI restano da definire
insieme al codice iniziale dell'utente. FP-060 è soltanto preparato, non concluso.

Riferimenti: [Angular CLI](https://angular.dev/cli),
[compatibilità delle versioni](https://angular.dev/reference/versions),
[contratto REST Fleet API](../../docs/09_SPECIFICA_API.md).
