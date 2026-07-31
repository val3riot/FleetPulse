# ADR-005 — Redis come cache ricostruibile

## Stato

Accettata.

## Decisione

Redis conserva soltanto la projection dello stato più recente.

## Conseguenze

- nessuna perdita permanente in caso di cache loss;
- fallback PostgreSQL obbligatorio;
- eventual consistency;
- necessità di cache repair.
