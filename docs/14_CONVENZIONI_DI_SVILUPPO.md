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
