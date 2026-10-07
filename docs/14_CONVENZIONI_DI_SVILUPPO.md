# Convenzioni di sviluppo

## 1. Commit message

FleetPulse adotta la convenzione Conventional Commits.

Formato:

```text
<type>(<scope>): <descrizione imperativa>
```

Il riferimento alla ticket viene aggiunto nel footer, separato dal titolo da una
riga vuota:

```text
Refs: FP-<numero>
```

### Type consentiti

- `feat`: nuova funzionalità;
- `fix`: correzione di un difetto;
- `build`: modifiche al sistema di build o alle dipendenze;
- `docs`: modifiche alla documentazione;
- `test`: aggiunta o modifica di test;
- `refactor`: modifica interna senza variazioni funzionali;
- `perf`: miglioramento delle prestazioni;
- `ci`: modifiche alla continuous integration;
- `chore`: attività di manutenzione non comprese negli altri type.

Lo scope identifica l'area interessata, per esempio `parent`, `gateway`,
`processor`, `api`, `simulator`, `contracts`, `protocol`, `frontend` o `docs`.

### Esempi

```text
feat(gateway): accept length-prefixed telemetry frames
```

```text
build(parent): initialize Maven multi-module build
```

```text
docs(frontend): define Fleet Dashboard functional scope
```

Esempio completo con riferimento alla ticket:

```text
build(repository): initialize FleetPulse project structure

Refs: FP-001
```

## 2. Logging applicativo — FP-040

- Usare SLF4J fluent (`atInfo`, `atDebug`, `atWarn`, `atError`) con
  `addKeyValue` per dati interrogabili; le coppie nel testo non sostituiscono
  campi strutturati. `event.action` è un nome semantico stabile, indipendente
  dal nome della classe. Non cambiare eventi esistenti senza aggiornare docs.
- Aggiungere messageId/vehicleId quando disponibili e classificazioni finite
  degli errori. Non serializzare entità, DTO, body o frame nei log; non passare
  messaggi/Throwable applicativi potenzialmente sensibili al logger.
- INFO per esiti principali; DEBUG per dettagli; WARN per degradazione;
  ERROR per failure terminali/inattese. Preservare warning rate limited e
  contatori completi. Non aumentare i livelli delle librerie indiscriminatamente.
- MDC solo per contesti di esecuzione definiti: requestId nel filtro HTTP,
  connectionId nel worker TCP. Salvare/ripristinare il contesto precedente in
  finally, incluso il percorso d'errore. Non assumere propagazione a thread
  o task asincroni; i dati degli eventi Kafka vengono passati esplicitamente.
- ECS è il default in tutti i profili; testo solo con override esplicito.
  Header UUID X-Request-ID e limiti di dispatch in docs/11 e ADR-013.

## 3. Metriche applicative — FP-041

- Iniettare MeterRegistry nei componenti di osservabilità; evitare registry
  statici/globali o una nuova metrica equivalente a quella già strumentata.
- Nomi, trigger, unità ed esiti devono rispettare il catalogo normativo docs/11.
  Registrare label con valori finiti; identificativi e dati liberi sono log,
  non dimensioni metriche. Ogni tentativo deve contare una sola volta.
- Usare Timer.Sample e clock monotono per durate locali, con stop garantito
  anche su failure. Rispettare il confine transazionale: una chiamata a save
  dentro una transazione non dimostra che il commit sia riuscito.
- Distinguere tentativi/retry da nuove persistenze e duplicati; distinguere
  persistenza, projection Redis, conferma Kafka e preparazione ACK.
- Configurare histogram nell'applicazione per i Timer selezionati; non abilitare
  percentili in librerie condivise. Preferire histogram aggregabili in Prometheus.
- Testare i punti di conteggio, failure/replay e confini temporali con un clock
  controllato; evitare sleep e soglie di durata dipendenti dalla macchina.
  Verificare anche nomi/tag/bucket sull'export Prometheus reale.
