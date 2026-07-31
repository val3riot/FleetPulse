# ADR-003 — Kafka tra gateway e processor

## Stato

Accettata.

## Decisione

La telemetria accettata viene pubblicata su Kafka ed elaborata in modo asincrono.

## Conseguenze positive

- temporal decoupling;
- buffering;
- replay;
- restart del processor;
- parallelismo per partition.

## Conseguenze negative

- eventual consistency;
- duplicate delivery;
- failure mode aggiuntivi.
