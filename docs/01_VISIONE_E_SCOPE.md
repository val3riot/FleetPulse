# Visione e scope

## 1. Scopo

FleetPulse fornisce una reference architecture per acquisire, elaborare e consultare la telemetria generata da una flotta commerciale simulata.

Il sistema riceve flussi continui da client virtuali, separa l'acquisizione dall'elaborazione, conserva lo storico delle osservazioni ed espone lo stato operativo più recente.

## 2. Obiettivi del prodotto

FleetPulse deve:

1. accettare più connessioni di telemetria concorrenti;
2. ricostruire correttamente i confini dei messaggi applicativi sopra TCP;
3. disaccoppiare l'acquisizione dall'elaborazione downstream;
4. tollerare la consegna duplicata degli eventi;
5. conservare lo storico della telemetria;
6. fornire accesso a bassa latenza allo stato più recente;
7. generare alert di manutenzione deterministici;
8. esporre segnali operativi sufficienti per diagnosticare i guasti;
9. essere eseguibile localmente tramite una topologia container riproducibile.

## 3. Stakeholder

| Stakeholder | Interesse |
|---|---|
| Fleet operator | Consultare veicoli, stato corrente, storico e alert |
| Operations engineer | Gestire la piattaforma e diagnosticare problemi |
| Application integrator | Consumare REST resource ed event contract |
| Maintainer | Estendere i servizi ed evolvere gli schema in modo controllato |

## 4. Scope

### Incluso

- registrazione di veicoli sintetici;
- generazione di telemetria simulata;
- trasporto TCP persistente;
- framing e validazione dei messaggi;
- elaborazione asincrona tramite Kafka;
- persistenza storica PostgreSQL;
- cache Redis dello stato più recente;
- regole di alert di manutenzione;
- REST API;
- log, metriche e health endpoint;
- deployment locale con Docker Compose;
- unit test, integration test ed end-to-end test.

### Escluso

- integrazione con veicoli reali;
- CAN, LIN o protocolli telematici proprietari;
- billing;
- identity federation;
- autorizzazione multi-tenant;
- ottimizzazione dei percorsi;
- predictive maintenance;
- analisi geospaziali avanzate;
- infrastruttura multi-zone di produzione;
- applicazioni mobile.

## 5. Obiettivi di qualità

### 5.1 Reliability

Gli eventi accettati devono restare elaborabili dopo indisponibilità temporanee dei componenti downstream. Una consegna duplicata non deve creare side effect duplicati.

### 5.2 Observability

Un operatore deve poter capire se un messaggio è stato rifiutato tecnicamente
dal gateway, accodato in Kafka, rifiutato dal dominio nel processor, fallito
durante l'elaborazione o persistito correttamente.

### 5.3 Maintainability

Trasporto, regole applicative, persistenza e interfacce esterne devono restare separati.

### 5.4 Performance

Il deployment locale di riferimento deve sostenere il carico sintetico documentato senza crescita illimitata delle risorse.

### 5.5 Recoverability

Il riavvio di un servizio non deve richiedere correzioni manuali dei dati negli scenari di guasto previsti.

## 6. Confine del sistema

FleetPulse inizia quando un client simulato apre una connessione TCP e termina nelle REST API, nei persistent store e nei segnali operativi prodotti dalla piattaforma.
