# ADR-013 — Structured logging ECS e correlazione

- Stato: accettato
- Riferimento: FP-040
- Data: 2026-10-07

## Contesto

Le quattro applicazioni devono emettere log interrogabili con formato uniforme,
identificativi di telemetria e correlazione HTTP, senza payload sensibili.

## Decisione

Usare JSON ECS nativo di Spring Boot sulla console in tutti i profili,
con campi SLF4J fluent key/value e MDC limitato al contesto di esecuzione.
Il testo è un override esplicito; non cambia automaticamente con `local`.
`X-Request-ID` riutilizza un unico UUID valido, altrimenti genera un UUID,
e viene restituito anche sulle risposte di errore. Il contesto va ripristinato
in `finally`; nessuna propagazione implicita a task asincroni.

`requestId` identifica la richiesta HTTP, `messageId` il messaggio di telemetria.
I contratti REST/TCP/Kafka e Redis restano quelli definiti nei rispettivi docs.
Non si introduce tracing distribuito. Le eccezioni applicative sono classificate
senza messaggi o stacktrace potenzialmente sensibili.

## Conseguenze

Campi numerici sono interrogabili senza parsing del messaggio leggibile.
I client possono comunicare il request ID per ricostruire una failure.
Un header diagnostico invalido non rende invalida una richiesta applicativa.
Le librerie mantengono il loro logging nativo in ECS: aumentarne i livelli
richiede attenzione ai dati emessi. Per dettagli e limiti operativi si veda
[Observability](../11_OBSERVABILITY.md).

## Fonte tecnica

[Spring Boot — Structured logging](https://docs.spring.io/spring-boot/reference/features/logging.html#features.logging.structured).
