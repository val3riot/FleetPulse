# Vehicle Simulator

Il modulo esegue un'applicazione Spring Boot non-web che registra una flotta
sintetica tramite Fleet API e invia telemetria al gateway su connessioni TCP
persistenti con framing length-prefixed.

## Comportamento

All'avvio, se `SIMULATOR_ENABLED=true`, il simulator:

1. genera le identità deterministiche `FP-SIM-001..N` e `SIM001..N`;
2. cerca ogni veicolo tramite Fleet API e lo crea soltanto se assente;
3. crea un virtual thread e uno stato runtime indipendente per veicolo;
4. apre una connessione TCP persistente e invia un frame a ogni intervallo;
5. incrementa il sequence number soltanto dopo una scrittura completata.

Il profilo `NORMAL` mantiene velocità, temperatura motore e tensione batteria
nei range nominali, incrementa l'odometro in base a velocità e intervallo e
applica piccole variazioni alle coordinate iniziali di Roma.

## Configurazione

| Variabile | Default | Descrizione |
|---|---:|---|
| `SIMULATOR_ENABLED` | `false` | Abilita provisioning e workload |
| `SIMULATOR_VEHICLE_COUNT` | `5` | Numero di veicoli sintetici |
| `SIMULATOR_FLEET_API_BASE_URL` | `http://localhost:8080` | Base URL di Fleet API |
| `SIMULATOR_GATEWAY_HOST` | `localhost` | Host del gateway TCP |
| `SIMULATOR_GATEWAY_PORT` | `7000` | Porta del gateway TCP |
| `SIMULATOR_GATEWAY_CONNECT_TIMEOUT` | `3s` | Timeout di ogni tentativo TCP |
| `SIMULATOR_SEND_INTERVAL` | `1s` | Intervallo tra invii riusciti |
| `SIMULATOR_SHUTDOWN_GRACE_PERIOD` | `5s` | Attesa massima dei workload allo stop |
| `SIMULATOR_RECONNECT_INITIAL_BACKOFF` | `250ms` | Prima attesa di reconnect |
| `SIMULATOR_RECONNECT_MAX_BACKOFF` | `5s` | Limite superiore del backoff |
| `SIMULATOR_RECONNECT_MAX_ATTEMPTS` | `10` | Tentativi totali per ciclo di reconnect |
| `SIMULATOR_RECONNECT_JITTER_RATIO` | `0.2` | Jitter proporzionale, tra `0` e `1` |
| `SIMULATOR_VEHICLE_SERVICE_INTERVAL_KM` | `15000` | Intervallo manutenzione |
| `SIMULATOR_VEHICLE_INITIAL_ODOMETER_KM` | `10000` | Odometro iniziale |

Tutte le durate accettano la sintassi Spring Boot, per esempio `250ms`, `3s`
o `1m`. Le proprietà vengono validate all'avvio e una configurazione non valida
impedisce la partenza.

## Reconnect e failure semantics

Il ritardo raddoppia da `initial-backoff` fino a `max-backoff`; il jitter evita
che tutti i veicoli tentino di riconnettersi nello stesso istante. La
progressione viene azzerata dopo una connessione riuscita. Esauriti i tentativi
configurati, il workload del veicolo termina e registra l'errore.

Una scrittura TCP fallita è ambigua: parte del frame potrebbe essere arrivata.
Il simulator chiude la socket ma non reinvia automaticamente lo stesso
messaggio, perché il retry guidato da ACK è fuori dallo scope di FP-016. Lo
stato e il sequence number avanzano solo dopo una scrittura completata.

## Shutdown

Il lifecycle Spring chiude prima tutte le socket, interrompe poi i virtual
thread e attende la terminazione dell'executor per il grace period configurato.
La socket in fase di connessione è chiudibile dal lifecycle e la chiusura
concorrente sblocca anche le scritture pendenti. `SIGTERM` del container attiva
lo stesso percorso.

## Esecuzione

Da host, con Fleet API e gateway raggiungibili:

```bash
SIMULATOR_ENABLED=true \
SIMULATOR_VEHICLE_COUNT=5 \
./mvnw --projects simulators/vehicle-simulator --also-make spring-boot:run
```

Con Docker Compose, impostare `SIMULATOR_ENABLED=true` in `.env` e avviare:

```bash
docker compose up --build -d vehicle-simulator
docker compose logs -f vehicle-simulator
```

Lo stack Compose corrente non configura il listener TCP del Telemetry Gateway:
nel gateway non e ancora presente un `FrameHandler` di produzione. Il comando
verifica quindi provisioning, avvio e comportamento di reconnect del simulator,
ma il flusso telemetrico end-to-end verso Kafka restera in attesa del relativo
step di integrazione del gateway. Non forzare `GATEWAY_TCP_ENABLED=true` finche
quel componente non e disponibile, perche il gateway fallirebbe il bootstrap.

## Test

```bash
./mvnw --projects simulators/vehicle-simulator --also-make test
```

La suite comprende unit test deterministici per configurazione, provisioning,
profilo, codec, backoff e lifecycle, più uno smoke test che avvia il contesto
Spring, simula Fleet API e verifica un frame su una socket TCP reale.

Non fanno parte di FP-016 retry basato su ACK, profili di guasto, accesso
diretto a PostgreSQL, Kafka awareness o metriche custom del simulator.
