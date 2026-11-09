# Changelog

## 0.1.0 — 2026-11-09

Prima versione del MVP per sviluppo e demo locale, distribuita come pre-release.
La versione del prodotto è condivisa fra moduli Maven e frontend; REST, messaggi
TCP ed eventi Kafka mantengono i contratti di versione 1.

### Funzionalità

- Acquisizione TCP con framing, validazione, ACK/NACK correlati e pubblicazione Kafka.
- Persistenza dello storico PostgreSQL, stato corrente Redis, gestione dei duplicati
  e recupero dei messaggi dopo indisponibilità temporanee del processor.
- API REST per veicoli, stato, storico paginato e alert di manutenzione.
- Dashboard Angular con ricerca, creazione/dettaglio/disabilitazione veicoli,
  polling con pausa persistente nella sessione, storico e gestione degli alert.
- Deployment Docker Compose con migrazioni automatiche, immagini ufficiali fissate
  per digest, processi non-root e limiti di risorse; metriche Prometheus e Grafana.
- Verificatori isolati per demo, carico, guasti e hardening; CI con test backend,
  frontend e Python, prova end-to-end e artefatti diagnostici.

### Limiti noti

- Solo host fidato e dati sintetici: non è una distribuzione pronta per produzione.
  Mancano autenticazione/autorizzazione REST e TCP, TLS Kafka, privilegi DB separati
  e gestione centralizzata dei segreti.
- CVE residue nelle immagini ufficiali: la CI funzionale e l’audit npm non
  sostituiscono la scansione Docker. Il consolidamento locale non attesta uno
  scan privo di vulnerabilità; non sono introdotti fork o patch manuali dei vendor.
- Accessibilità verificata automaticamente e con le prove manuali dichiarate;
  nessuna certificazione completa di screen reader, browser, zoom o reflow.
- I test di carico hanno durata e fixture definite; non qualificano automaticamente
  altri ambienti, carichi, architetture o funzionamento prolungato.

Comandi e confini delle verifiche: [CI](infrastructure/ci/README.md),
[demo](infrastructure/demo/README.md), [carico](infrastructure/load/README.md)
e [container](infrastructure/hardening/README.md).
