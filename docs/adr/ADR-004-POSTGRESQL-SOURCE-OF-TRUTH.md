# ADR-004 — PostgreSQL come source of truth

## Stato

Accettata.

## Decisione

PostgreSQL è lo storage autorevole per vehicle registry, telemetry history e alert.

## Conseguenze

- migration obbligatorie;
- dipendenza dalla disponibilità di PostgreSQL;
- idempotency rafforzata tramite constraint.
