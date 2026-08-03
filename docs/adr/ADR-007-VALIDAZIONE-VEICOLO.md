# ADR-007 — Validazione del veicolo nel telemetry processor

## Stato

Accettata.

## Contesto

Il protocollo TCP documentava `UNKNOWN_VEHICLE` e `VEHICLE_DISABLED` come
possibili esiti sincroni del Telemetry Gateway. Il gateway, tuttavia, non
dispone del registry dei veicoli, non accede a PostgreSQL e non chiama Fleet
API. Non può quindi determinare autorevolmente l'esistenza e lo stato operativo
del veicolo.

## Problema

Occorre stabilire dove verificare che il veicolo esista e sia `ACTIVE`, senza
rendere ambiguo il significato dell'ACK TCP e senza contraddire i confini dei
servizi.

## Decision driver

- preservare throughput e disponibilità dell'ingestione;
- evitare PostgreSQL nel critical path del gateway;
- evitare chiamate sincrone gateway → Fleet API;
- mantenere chiari i confini dei servizi;
- mantenere il gateway focalizzato sul trasporto;
- rendere inequivocabile il significato di `ACCEPTED`;
- limitare la complessità dell'MVP;
- garantire osservabilità dei rifiuti di dominio.

## Alternative considerate

### Gateway con accesso read-only a PostgreSQL

Consente una risposta sincrona e autorevole e permette al gateway di produrre
immediatamente `UNKNOWN_VEHICLE` e `VEHICLE_DISABLED`.

Introduce però accoppiamento tra gateway, database e schema `vehicles`.
Disponibilità e latenza di PostgreSQL influenzerebbero direttamente
l'ingestione, aumentando connessioni e query e rendendo il database parte del
critical path di ogni messaggio. Contraddice inoltre i confini architetturali
correnti e richiederebbe probabilmente cache e ulteriori strategie di
resilienza.

Esito: rifiutata per l'MVP.

### Registry locale alimentato da eventi

Consente lookup locali veloci, non richiede chiamate sincrone verso Fleet API o
PostgreSQL e mantiene il gateway indipendente dal database.

Richiede però eventi sul lifecycle dei veicoli, bootstrap iniziale, replay,
riconciliazione e gestione dei riavvii. Introduce consistenza eventuale e può
produrre decisioni temporaneamente basate su dati obsoleti. Richiede inoltre la
gestione di duplicati, ordine e versionamento degli eventi, con una complessità
eccessiva per l'MVP.

Esito: rinviata; possibile evoluzione futura.

### Validazione nel Telemetry Processor

Preserva throughput e disponibilità del gateway, non introduce nuove
dipendenze sincrone, mantiene il gateway focalizzato sul trasporto e PostgreSQL
fuori dal critical path TCP. Kafka può assorbire i messaggi anche quando il
processor è offline.

Comporta l'ingresso in Kafka di telemetria successivamente rifiutata, non
permette al client TCP di ricevere immediatamente il rifiuto di dominio e
richiede un esito asincrono osservabile e una semantica ACK precisa.

Esito: accettata per l'MVP.

## Decisione

La verifica dell'esistenza e dello stato operativo del veicolo viene eseguita
dal Telemetry Processor.

Il Telemetry Gateway valida esclusivamente:

- framing e dimensione del frame;
- codifica e sintassi JSON;
- struttura e vincoli tecnici del payload;
- capacità di accettazione e backpressure;
- pubblicazione su Kafka.

Il gateway non accede alla tabella `vehicles`, non interroga PostgreSQL, non
chiama Fleet API e non decide se un veicolo è `ACTIVE` o `DISABLED`.

## Semantica ACK/NACK

`ACCEPTED` significa esclusivamente che il gateway ha accettato il frame, la
validazione tecnica è riuscita e Kafka ha confermato la pubblicazione.

Non significa che il veicolo esista o sia `ACTIVE`, che la telemetria sia stata
persistita, che Redis sia stato aggiornato, che un alert sia stato generato o
che il processor abbia completato l'elaborazione.

I NACK sincroni restano limitati alle condizioni osservabili dal gateway.
`UNKNOWN_VEHICLE` e `VEHICLE_DISABLED` non sono NACK TCP.

## Modello dei rifiuti asincroni

Il processor considera `UNKNOWN_VEHICLE` e `VEHICLE_DISABLED` rifiuti
permanenti di dominio. In questi casi:

- non inserisce righe in `telemetry_samples`;
- non aggiorna Redis;
- non genera alert;
- produce log strutturati e metriche distinte per `reason`;
- pubblica un evento osservabile su `telemetry.rejected.v1`.

Il contratto minimo dell'evento di rifiuto comprende `messageId`, `vehicleId`,
`reason`, `rejectedAt`, source topic, source partition e source offset.

`telemetry.rejected.v1` contiene messaggi elaborati correttamente ma rifiutati
dal dominio. `telemetry.dead-letter.v1` contiene invece messaggi non elaborabili
o errori tecnici che hanno esaurito la politica di retry.

## Failure model

| Condizione | Comportamento |
|---|---|
| Kafka non disponibile al gateway | NACK tecnico, nessun `ACCEPTED` |
| Processor non disponibile | Kafka conserva i messaggi |
| PostgreSQL temporaneamente indisponibile | Il processor non completa il record e applica retry |
| Veicolo inesistente | Rifiuto asincrono `UNKNOWN_VEHICLE` |
| Veicolo disabilitato | Rifiuto asincrono `VEHICLE_DISABLED` |
| Redis non disponibile | PostgreSQL resta source of truth; nessuna perdita della telemetria valida |
| Messaggio duplicato | Idempotenza tramite `messageId` |
| Rejection topic temporaneamente indisponibile | Retry tecnico, nessuna perdita silenziosa |
| Errore tecnico non recuperabile | Dead-letter secondo la policy prevista |

Un rifiuto di dominio non causa retry indefiniti. L'elaborazione è conclusa
soltanto dopo che il rifiuto è stato reso osservabile. Se la pubblicazione del
rejection event fallisce, il problema è tecnico: il record originale non deve
essere perso e l'offset non deve essere avanzato in modo da nascondere la
perdita. Il dettaglio transazionale è demandato alle ticket di implementazione.

## Conseguenze positive

- gateway indipendente da Fleet API e PostgreSQL;
- throughput TCP non vincolato dalla latenza del registry;
- buffering Kafka disponibile durante i fermi del processor;
- responsabilità tecniche e di dominio separate;
- semantica di `ACCEPTED` unica e verificabile.

## Conseguenze negative

- un messaggio può ricevere `ACCEPTED` ed essere poi rifiutato dal dominio;
- il client TCP non riceve immediatamente l'esito di eligibility;
- è necessario gestire e osservare un secondo esito Kafka;
- la pubblicazione del rifiuto richiede retry e corretta gestione dell'offset.

## Alternative future

Un registry locale event-driven nel gateway potrà essere rivalutato se sarà
necessario fornire feedback sincrono al client. L'evoluzione richiederà eventi
autorevoli sul lifecycle dei veicoli, bootstrap e riconciliazione espliciti.

## Ticket di implementazione collegate

- FP-010 finalizza i contratti TCP coerenti con questa decisione;
- FP-018 implementa la validazione del veicolo nel processor;
- FP-020 definisce i topic Kafka normativi;
- FP-022 introduce il consumer baseline del processor;
- FP-026 implementa la pubblicazione dei rifiuti e delle dead-letter.
