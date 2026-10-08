# ADR-012 — Strategia di accesso dati JPA

## Stato

Accettata: registra la scelta dell'utente del 2026-10-07 di usare JPA per
FP-037 e uniformare gli adapter PostgreSQL esistenti. Implementazioni nei
commit `ec117f2` e `256a0ce`; non introduce una nuova riscrittura delle query.

## Contesto

Fleet API e telemetry-processor usavano repository JPA e adapter Spring JDBC.
Le letture latest-state, stato veicolo e soglia manutenzione sono esprimibili
con JPA; per la dashboard sono già disponibili entity e projection tipizzate.
Il progetto deve mantenere contratti, confini transazionali e limiti database,
riducendo duplicazioni del mapping e rendendo esplicita la scelta delle query.

## Decisione

Usare Spring Data JPA come scelta predefinita per l'accesso PostgreSQL:

- Query derivate per filtri fissi semplici.
- Specifications per ricerche dinamiche con filtri opzionali, come veicoli e
  alert; comporre i predicati valorizzati secondo la semantica del contratto.
- Query JPQL/HQL dichiarate e projection tipizzate per aggregazioni, ranking
  e letture top-N; mantenere i limiti nel database.
- Query native o Spring JDBC soltanto quando una necessità specifica o una
  misura giustifica SQL esplicito, documentando motivo e verifiche.

Specifications non è obbligatoria per ogni query. Lo storico attuale ha
vehicleId e due estremi obbligatori e conserva la query derivata. Dashboard,
latest-state e lookup processor conservano le query fisse/projection.

I service possiedono il confine transazionale delle operazioni composte.
Le porte latest-state/registry/alert-vehicle restano separate dai repository.
Il processor usa un read model locale @Immutable dei veicoli, con repository
che espone soltanto letture; non importa entity della Fleet API e non aggiunge
relazioni o cascade per uniformare la tecnologia.

Preservare il commit atomico sample/alert e il successivo aggiornamento Redis.
La transazione del fallback latest-state termina prima del repair Redis.
La dashboard mantiene quattro SELECT nello snapshot REPEATABLE READ.

## Conseguenze e limiti

JPA usa il driver JDBC per comunicare col database. Questa decisione elimina
query Spring JDBC dirette dal codice applicativo attuale, non JDBC dallo stack.
JDBC nei test resta utile per fixture e EXPLAIN; Flyway continua a gestire SQL,
indici e migration. Nessuna migration deriva dalla sola uniformazione.

Specifications rende componibili i filtri, ma non garantisce prestazioni:
valutare SQL generato, indici, cardinalità, COUNT e offset su dati rappresentativi.
Page mantiene i totali previsti dal contratto; Slice/cursor non sono sostituzioni
trasparenti. Evitare fetch di collezioni nelle query paginate; eventuali join
richiedono verifiche su duplicati, count e limite effettivo.

HQL può usare funzionalità oltre JPQL: le query vanno verificate col provider
Hibernate e PostgreSQL del progetto, senza assumere portabilità universale.
La preferenza JPA è motivata da coerenza e riuso del mapping; non dimostra un
vantaggio di throughput rispetto a JDBC.

## Alternative considerate

- Mantenere tutti gli adapter JDBC: scelta tecnicamente valida, ma mantiene
  mapping manuali per query già esprimibili nel modello JPA locale.
- Usare Specifications ovunque: aggiunge complessità alle letture fisse e alle
  aggregazioni senza un beneficio di composizione.
- Condividere entity fra servizi: lega il modello persistence dei servizi;
  sono preferiti mapping locali dei dati necessari.

## Verifica

Preservare Optional per dati assenti, enum/UUID/Instant, filtri e tie-breaker,
limite SQL, mapping degli errori e confini commit/Redis. Per projection mirate
verificare query effettive e assenza di entity caricate. I criteri di verifica
sono definiti nella strategia di test; i contract test non richiedono una
nuova conversione delle query.

## Riferimenti

- [Architettura](../05_ARCHITETTURA_DI_SISTEMA.md)
- [Contratto API](../09_SPECIFICA_API.md)
- [Strategia di test](../12_STRATEGIA_DI_TEST.md)
- [ADR-010 — Fallback State API](ADR-010-STATE-API-FALLBACK.md)
- [Spring Data JPA — Specifications](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html)
- [Spring Data JPA — Projections](https://docs.spring.io/spring-data/jpa/reference/repositories/projections.html)
- [Spring Framework — JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html)
