# ADR-001 — Confini dei servizi

## Stato

Accettata.

## Contesto

Il sistema deve separare acquisizione di rete, elaborazione asincrona e query senza introdurre troppi microservizi.

## Decisione

Usare tre servizi applicativi:

- Telemetry Gateway;
- Telemetry Processor;
- Fleet API.

Vehicle Simulator è un workload generator.

## Conseguenze

### Positive

- failure domain chiari;
- processor riavviabile indipendentemente;
- gateway privo di responsabilità di persistenza;
- query separate dal carico di ingestione.

### Negative

- più configurazione;
- consistenza distribuita;
- end-to-end test infrastrutturali.
