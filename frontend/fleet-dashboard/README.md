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

## Base tecnica — FP-060

Angular 22.2.2, standalone e zoneless, routing predisposto, TypeScript strict,
CSS, HttpClient e Vitest. La shell offre navigazione Dashboard, Veicoli e Alert e una pagina per gli
indirizzi inesistenti. Le pagine operative e i servizi REST sono da implementare.
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
npm run verify        # lint, formato, test e build produzione
npm run lint          # ESLint TypeScript e template Angular
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
- `src/styles.css`: stili globali.

I componenti generati dal CLI sono standalone e usano CSS. Le funzionalità
possono essere organizzate per dominio sotto `src/app/`, man mano che vengono
implementate. Le pagine si trovano sotto `features/`, i componenti condivisi sotto `shared/`.
FP-060 introduce la navigazione; la UI operativa segue nelle prossime FP.

Riferimenti: [Angular CLI](https://angular.dev/cli),
[compatibilità delle versioni](https://angular.dev/reference/versions),
[contratto REST Fleet API](../../docs/09_SPECIFICA_API.md).

## Accettazione manuale FP-060

Non richiede il backend.

1. Eseguire `npm ci` e `npm run verify`: tutti i controlli devono passare.
2. Eseguire `npm start` e aprire `http://127.0.0.1:4200/`: l'indirizzo diventa
   `/dashboard`, con titolo Dashboard e link attivo.
3. Aprire Veicoli e Alert dalla navigazione: cambiano indirizzo, titolo della
   scheda e contenuto; ogni pagina indica la FP che introdurrà i dati reali.
4. Ricaricare direttamente `/vehicles`: rimane la pagina Veicoli.
5. Aprire `/non-esiste`: compare Pagina non trovata; Torna alla dashboard funziona.
6. Usare Indietro/Avanti: contenuto e voce attiva seguono l'indirizzo.
7. Usare Tab: il primo collegamento visibile al focus è Vai al contenuto;
   i link sono raggiungibili da tastiera. Ridurre la finestra a 375 px:
   navigazione e testo rimangono leggibili senza scorrimento orizzontale.

I test automatici coprono redirect iniziale, tre rotte, titoli e link attivi,
navigazione tramite click, pagina non trovata con ritorno e destinazione del
collegamento al contenuto. Aspetto e comportamento nel browser sono verificabili
con la checklist manuale.

## Regola di completamento delle prossime FP

Ogni funzionalità include test automatici del comportamento introdotto e degli
errori pertinenti, più una procedura manuale ripetibile con prerequisiti,
comandi, dati necessari e risultati attesi. I test vengono aggiunti nella stessa
FP; FP-067 consoliderà la copertura trasversale. `npm run verify` deve passare
prima di proporre la FP come completata.

Lint basato sulle configurazioni raccomandate TypeScript e Angular, inclusi i
controlli di accessibilità dei template: [angular-eslint](https://github.com/angular-eslint/angular-eslint/blob/main/docs/CONFIGURING_ESLINT.md).

### Layout condiviso

`App` contiene la struttura comune: collegamento al contenuto, `Header` e
`main` con `RouterOutlet`. L'header è isolato in `src/app/layout/header/` con
component, template e stile dedicati. Tutte le rotte attuali, inclusa la pagina
non trovata, usano questa struttura senza ricrearla durante la navigazione.
Anche lista, form e dettaglio previsti possono usare lo stesso contenitore;
i contenuti e le azioni specifiche appartengono alle singole pagine.

Un test verifica la permanenza delle stesse istanze DOM di header e main su
Dashboard, Veicoli, Alert e pagina non trovata, con un solo header/main e la
corretta voce attiva. Per la verifica manuale, passare fra queste pagine:
header e margini del contenuto restano uniformi; sulla pagina non trovata
nessuna voce della navigazione risulta attiva. Ripetere a larghezza 375 px.
