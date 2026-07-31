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

## Stato

Il Fleet Dashboard è progettato ma non ancora implementato. In questa fase non
sono presenti scaffolding applicativo, dipendenze Node, componenti UI, codice
JavaScript o TypeScript e immagini container.
