# Architettura di sistema

## 1. Stile architetturale

FleetPulse usa una piccola architettura distribuita con quattro confini
applicativi:

1. acquisizione;
2. elaborazione asincrona;
3. query API;
4. presentazione web.

La separazione crea failure domain indipendenti senza introdurre un servizio per ogni entità.

## 2. Responsabilità

### Vehicle Simulator

- genera profili sintetici;
- mantiene connessioni TCP;
- codifica i frame;
- gestisce sequence number;
- riceve ACK/NACK;
- applica reconnect con bounded backoff;
- genera duplicati e disconnessioni controllate.

### Telemetry Gateway

- gestisce il TCP listener;
- limita le connessioni;
- ricostruisce i frame;
- rifiuta input malformati;
- valida schema e vincoli tecnici del payload;
- applica backpressure;
- pubblica su Kafka;
- invia ACK solo dopo producer acknowledgement;
- espone metriche di connessione e frame.

Non accede a PostgreSQL o Fleet API, non mantiene un registry locale e non
valida esistenza o stato operativo del veicolo.

### Telemetry Processor

- consuma `telemetry.raw.v1`;
- verifica esistenza e stato operativo del veicolo in PostgreSQL;
- pubblica i rifiuti di dominio su `telemetry.rejected.v1`;
- garantisce application idempotency;
- persiste i sample;
- valuta le regole;
- persiste gli alert;
- aggiorna Redis;
- classifica errori transitori e permanenti;
- espone metriche.

### Fleet API

- gestisce i veicoli;
- espone stato corrente;
- espone storico;
- espone alert;
- pubblica OpenAPI;
- applica fallback PostgreSQL.

### Fleet Dashboard

- offre agli operatori la vista funzionale della flotta;
- mostra dashboard, veicoli, telemetria e alert;
- registra nuovi veicoli tramite Fleet API;
- aggiorna i dati con polling REST configurabile;
- comunica esclusivamente con Fleet API tramite HTTP/JSON.

Non accede direttamente a gateway, processor, simulator, Kafka, PostgreSQL,
Redis, Prometheus o Grafana. Grafana è un'interfaccia tecnica separata dedicata
all'observability.

## 3. Data flow nominale

1. Il simulator apre una connessione.
2. Invia un frame length-prefixed.
3. Il gateway ricostruisce il frame e applica la validazione tecnica.
4. Pubblica su Kafka con key `vehicleId`.
5. Dopo l'acknowledgement Kafka risponde `ACCEPTED` al simulator.
6. Il processor consuma.
7. Il processor verifica esistenza e stato del veicolo in PostgreSQL.
8. Per un veicolo `ACTIVE`, PostgreSQL persiste sample e alert e Redis viene
   aggiornato.
9. Per un veicolo sconosciuto o `DISABLED`, il processor non applica side
   effect e pubblica il rifiuto su `telemetry.rejected.v1`.
10. Fleet API espone i dati persistiti.

## 4. Flussi di consultazione

Il flusso funzionale destinato agli operatori è:

```text
Fleet Operator -> Fleet Dashboard -> Fleet API -> PostgreSQL/Redis
```

Fleet API mantiene il confine di accesso ai dati e applica il fallback da Redis
a PostgreSQL. Il Fleet Dashboard non conosce le dipendenze interne della
piattaforma.

Il flusso tecnico di observability rimane separato:

```text
Operations Engineer -> Grafana -> Prometheus -> metriche dei servizi
```

## 5. Confini di consistenza

### ACK del gateway

`ACCEPTED` significa esclusivamente che il gateway ha accettato il frame, la
validazione tecnica è riuscita e Kafka ha confermato la pubblicazione secondo
la producer acknowledgement policy.

Non significa che:

- il veicolo esista;
- il veicolo sia `ACTIVE`;
- PostgreSQL contenga già il sample;
- Redis sia già aggiornato;
- gli alert siano già stati creati;
- il processor abbia completato l'elaborazione.

`UNKNOWN_VEHICLE` e `VEHICLE_DISABLED` sono rifiuti asincroni del processor,
non NACK sincroni del gateway.

### Transazione PostgreSQL

Sample e alert derivati dovrebbero essere persistiti nella stessa transaction.

Redis viene aggiornato dopo la transazione autorevole.

### Ordering

L'ordine è garantito soltanto all'interno della Kafka partition. La key `vehicleId` preserva l'ordine per singolo veicolo.

## 6. Modello di concorrenza

Il gateway utilizza:

- un task per connessione;
- Java virtual threads per blocking socket I/O;
- `Semaphore` per il limite massimo;
- DTO immutabili;
- strutture thread-safe per i metadati;
- shutdown cooperativo.

I virtual threads non eliminano la necessità di timeout, limiti e backpressure.

## 7. Classificazione degli errori

| Tipo | Esempio | Gestione |
|---|---|---|
| Client error | Frame troppo grande | NACK o chiusura |
| Domain rejection | Veicolo sconosciuto o disabilitato | Rejection event, nessun side effect |
| Permanent message error | Contratto Kafka non supportato | Dead-letter topic |
| Transient infrastructure error | Database temporaneamente irraggiungibile | Bounded retry |
| Cache error | Redis non disponibile | Fallback |
| Capacity error | Limite connessioni | Rifiuto esplicito |
| Internal defect | Violazione inattesa | Log, metriche e isolamento dell'operazione |
