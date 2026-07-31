# ADR-006 — At-least-once con application idempotency

## Stato

Accettata.

## Decisione

Assumere duplicate delivery e proteggere i side effect con idempotency key stabili e uniqueness constraint.

## Conseguenze

- i duplicati sono eventi normali;
- i retry sono sicuri per le operazioni protette;
- non viene dichiarato exactly-once end-to-end;
- ogni nuovo side effect richiede una strategia di idempotency.
