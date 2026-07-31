# ADR-002 — Protocollo TCP length-prefixed

## Stato

Accettata.

## Decisione

Ogni payload JSON UTF-8 è preceduto da una lunghezza unsigned di quattro byte big-endian.

## Alternative

- newline-delimited JSON;
- HTTP;
- binary schema.

## Conseguenze

- gestione obbligatoria delle partial read;
- limite esplicito ai frame;
- payload leggibile;
- versioning del protocollo.
