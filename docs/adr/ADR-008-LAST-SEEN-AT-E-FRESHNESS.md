# ADR-008 — Significato di lastSeenAt e freshness dello stato

## Stato

Accettata.

## Contesto

La projection Redis e la State API espongono `lastSeenAt`, usato per
determinare la freshness della telemetria, cioè quanto è recente la misura. Il contratto non specificava quale
timestamp della rilevazione alimentasse questo campo.

Una rilevazione può essere prodotta alle 10:00, ricevuta dal gateway alle
10:05 ed elaborata alle 10:06. Usare la ricezione o l'elaborazione come momento
della misura nasconderebbe il ritardo di trasporto o l'arretrato Kafka.

## Decisione

`lastSeenAt` corrisponde a `observedAt` della rilevazione selezionata come
stato corrente. Rappresenta il momento della misura, in UTC, e non il momento
dell'ultima connessione del dispositivo o dell'aggiornamento della cache.

Il mapping deve essere identico nei tre percorsi:

- aggiornamento della projection da parte del Telemetry Processor;
- risposta della Fleet API tramite fallback PostgreSQL;
- ricostruzione della cache dopo il fallback.

`receivedAt` e `processedAt` mantengono il proprio significato di ricezione
nel gateway ed elaborazione nel processor. Non vengono modificati e non
sostituiscono `observedAt` nel calcolo di `lastSeenAt`.

La freshness esprime l'età della misura rispetto all'orario corrente. Il TTL
esprime invece la durata della chiave Redis: scrittura, ricostruzione e rinnovo
del TTL non devono rendere artificialmente recente una misura vecchia.

## Alternative considerate

- **`receivedAt`:** misura quando il gateway ha ricevuto il messaggio, ma può
  far apparire recente una rilevazione arrivata in ritardo.
- **`processedAt`:** misura l'elaborazione, ma può far apparire recenti tutte
  le rilevazioni recuperate da un arretrato Kafka.
- **`observedAt`:** descrive il momento a cui si riferiscono i valori mostrati;
  scelta accettata per la freshness dello stato telemetrico.

## Conseguenze e limiti

- Un hit Redis può restituire una misura vecchia; il TTL positivo non ne
  garantisce la freshness.
- La freshness non dimostra l'allineamento tra Redis e PostgreSQL: anche una
  misura recente può essere superata da un'altra già persistita.
- La scelta assume un orologio del dispositivo sufficientemente affidabile.
  Timestamp futuri o clock skew richiedono una policy esplicita; questo ADR
  non introduce correzioni silenziose o nuove regole di rifiuto.
- La scelta del campo non definisce l'ordinamento completo dello stato
  corrente: sequenze dopo riavvio, eventi fuori ordine e parità di timestamp
  sono disciplinati da [ADR-009](ADR-009-LATEST-STATE-PROJECTION.md),
  che definisce il confronto e i limiti da gestire nel fallback.
- [ADR-010](ADR-010-STATE-API-FALLBACK.md) definisce soglia e confronto esatto
  per `stale`, con un `Clock` iniettabile per verificarli deterministicamente.

## Verifiche previste

- Con osservazione alle 10:00, ricezione alle 10:05 ed elaborazione alle
  10:06, `lastSeenAt` deve essere 10:00.
- Per lo stesso sample, cache hit, fallback e cache ricostruita devono
  esporre lo stesso `lastSeenAt`.
- Replay e rinnovi della cache non devono sostituire il momento della misura
  con l'orario corrente.

## Riferimenti

- [ADR-005 — Redis come cache ricostruibile](ADR-005-REDIS-CACHE-RICOSTRUIBILE.md)
- [Modello dati](../07_DATA_MODEL.md)
- [Specifica API](../09_SPECIFICA_API.md)
- Ticket FP-030 e FP-031.

- [ADR-009 — Latest-state projection](ADR-009-LATEST-STATE-PROJECTION.md)
