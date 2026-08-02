# Requisiti

## 1. Requisiti funzionali

### RF-001 — Registrazione di un veicolo

Il sistema deve permettere di registrare un veicolo con:

- identificativo univoco generato dal backend;
- codice esterno;
- targa;
- intervallo di manutenzione;
- soglia chilometrica della prossima manutenzione.

Il backend deve assegnare al nuovo veicolo lo stato iniziale `ACTIVE`; il client
non può scegliere lo stato durante la registrazione.

### RF-002 — Abilitazione e disabilitazione della telemetria

Il sistema deve accettare telemetria soltanto dai veicoli in stato `ACTIVE`.

### RF-003 — Connessioni TCP

Il Telemetry Gateway deve accettare più TCP client contemporaneamente.

### RF-004 — Decodifica dei frame

Il gateway deve ricostruire frame length-prefixed indipendentemente dai confini delle socket read.

### RF-005 — Validazione

Il gateway deve validare:

- protocol version;
- frame size;
- identificativi obbligatori;
- sequence number;
- timestamp;
- intervalli numerici;
- esistenza e stato del veicolo.

### RF-006 — Pubblicazione su Kafka

Il gateway deve pubblicare la telemetria valida usando `vehicleId` come record key.

### RF-007 — Application acknowledgement

Il gateway deve inviare un ACK positivo soltanto dopo la producer acknowledgement di Kafka.

### RF-008 — Persistenza dello storico

Il Telemetry Processor deve persistere gli eventi validi in PostgreSQL.

### RF-009 — Elaborazione idempotente

Il ritrattamento dello stesso `messageId` non deve generare un secondo sample.

### RF-010 — Stato più recente

Il processor deve aggiornare in Redis lo stato più recente di ogni veicolo.

### RF-011 — Consultazione dello stato

Fleet API deve leggere lo stato da Redis quando disponibile e usare PostgreSQL come fallback.

### RF-012 — Consultazione dello storico

Fleet API deve esporre lo storico con filtri temporali e paginazione.

### RF-013 — Generazione degli alert

Il processor deve valutare regole configurabili dopo la persistenza della telemetria.

### RF-014 — Consultazione e aggiornamento degli alert

Fleet API deve permettere di leggere gli alert e applicare le transizioni supportate.

### RF-015 — Dead-letter topic

Gli eventi permanentemente non elaborabili devono essere pubblicati su una dead-letter topic con metadati diagnostici.

### RF-016 — Endpoint operativi

Ogni servizio applicativo deve esporre health endpoint e metriche.

### RF-017 — Contratto degli errori REST

Fleet API deve restituire errori strutturati con HTTP status, codice stabile,
messaggio, path e dettagli di validazione tipizzati. Le violazioni dei constraint
di unicità devono essere convertite negli stessi errori `409` anche in presenza
di richieste concorrenti.

### RF-018 — OpenAPI

Fleet API deve pubblicare un documento OpenAPI coerente con request, response,
paginazione, enum ed errori documentati.

### Requisiti del Fleet Dashboard

#### RF-FE-01 — Panoramica della flotta

Il Fleet Dashboard deve mostrare almeno:

- numero totale di veicoli;
- distribuzione per stato operativo;
- numero di veicoli che hanno trasmesso recentemente;
- numero di alert aperti;
- alert recenti o rilevanti.

#### RF-FE-02 — Elenco dei veicoli

Il Fleet Dashboard deve permettere di visualizzare, cercare e filtrare i veicoli,
incluso il filtro per stato operativo.

#### RF-FE-03 — Registrazione di un veicolo

Il Fleet Dashboard deve permettere all'operatore di registrare un veicolo tramite
Fleet API.

#### RF-FE-04 — Dettaglio del veicolo

Il Fleet Dashboard deve mostrare i dati anagrafici e lo stato operativo di un
veicolo selezionato.

#### RF-FE-05 — Ultima telemetria

Il Fleet Dashboard deve mostrare l'ultima telemetria disponibile e la relativa
freshness per il veicolo selezionato.

#### RF-FE-06 — Storico telemetrico

Il Fleet Dashboard deve permettere di consultare lo storico telemetrico di un
veicolo per intervallo temporale, con paginazione.

#### RF-FE-07 — Consultazione degli alert

Il Fleet Dashboard deve permettere di visualizzare e filtrare gli alert per
veicolo, tipo, severità e stato, oltre a consultarne il dettaglio.

#### RF-FE-08 — Aggiornamento periodico

Il Fleet Dashboard deve aggiornare i dati tramite polling REST configurabile. Un
intervallo di 3–5 secondi è indicativo per il deployment locale e non costituisce
un requisito rigido.

## 2. Requisiti non funzionali

### RNF-001 — Carico concorrente

Il deployment di riferimento deve supportare almeno 50 connessioni simulate con un frame ogni due secondi.

### RNF-002 — Uso limitato delle risorse

Il gateway deve applicare:

- limite alle connessioni attive;
- dimensione massima dei frame;
- read timeout;
- retry policy limitate.

### RNF-003 — Processing latency

Sotto il carico locale di riferimento, il p95 tra accettazione del gateway e persistenza dovrebbe restare inferiore a due secondi.

Il valore descrive l'ambiente locale e non costituisce uno SLO di produzione.

### RNF-004 — Idempotency

Eventi duplicati non devono duplicare sample o alert.

### RNF-005 — Cache degradation

L'indisponibilità di Redis non deve rendere inaccessibile la telemetria persistita.

### RNF-006 — Isolamento

Un client lento o malformato non deve terminare il gateway né bloccare client indipendenti.

### RNF-007 — Configurazione

I valori specifici dell'ambiente devono essere esterni all'applicazione.

### RNF-008 — Schema evolution

Le modifiche al database devono essere versionate con Flyway. Eventi e payload TCP devono avere una versione.

### RNF-009 — Traceability

I log devono includere `messageId` e `vehicleId` quando disponibili.

### RNF-010 — Riproducibilità

La topologia locale deve essere avviabile con Docker Compose senza creare manualmente risorse infrastrutturali.

### RNF-011 — Testability

Le integrazioni devono poter essere verificate tramite container temporanei.

### RNF-012 — Dati

Nel repository e negli ambienti di test devono essere usati soltanto dati sintetici.

## 3. Vincoli

- runtime Java 21;
- PostgreSQL come source of truth;
- Redis non autorevole e sostituibile;
- Kafka trattato come at-least-once;
- protocollo TCP con header di quattro byte seguito da JSON UTF-8;
- Kafka locale a singolo broker, non rappresentativo di produzione.
