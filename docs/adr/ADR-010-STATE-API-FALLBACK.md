# ADR-010 — State API, fallback e read repair

## Stato

Accettata nel confronto con l'utente il 2026-09-08, con revisione del percorso
iniziale: Redis viene letto prima di PostgreSQL. Per il progetto di studio si
accetta temporaneamente una chiave orfana fino alla scadenza, nei limiti indicati
sotto. Implementazione e test appartengono a FP-031.

## Decisioni

`GET /api/v1/vehicles/{vehicleId}/state` legge prima `vehicle:last:{vehicleId}`
da Redis. Un hit valido evita qualsiasi query PostgreSQL, anche quando la misura
è stale. Solo in caso di miss o failure il fallback verifica l'esistenza del
veicolo in PostgreSQL e poi cerca il sample. Lo stato `DISABLED` non
impedisce la consultazione della telemetria già persistita.

Un miss o un errore della cache (connessione, timeout, JSON non decodificabile,
identità del veicolo diversa dalla key) attiva il fallback. La query seleziona
una sola riga con ordinamento `observed_at DESC, sequence_number DESC, id DESC`.
Il terzo campo rende deterministica la scelta a parità completa senza modificare
la recency di ADR-009. La migration V2 aggiunge l'indice corrispondente e conserva
gli indici precedenti, usati da altri percorsi di lettura.

Veicolo assente e storico assente producono rispettivamente
`VEHICLE_NOT_FOUND` e `VEHICLE_STATE_NOT_AVAILABLE`, entrambi HTTP 404.
Un hit Redis resta servibile con PostgreSQL indisponibile. HTTP 503 si verifica
quando serve il fallback e PostgreSQL non è disponibile. La sola indisponibilità
Redis non impedisce la risposta. Gli errori PostgreSQL seguono il classificatore REST esistente;
un errore di programmazione o di query non viene trasformato in indisponibilità.

## Freshness

`lastSeenAt` resta `observedAt` in ogni percorso. `stale` è vero soltanto se
`Duration.between(lastSeenAt, clock.instant()) > staleAfter`. La property
`fleetpulse.api.state.stale-after` ha default `1m`, è configurabile e deve
essere almeno `1ms`. Alla soglia esatta la misura non è stale. Timestamp futuri
non vengono corretti e non sono stale; resta l'assunzione di ADR-008 sull'orologio
del dispositivo. Il clock UTC già disponibile nella Fleet API è iniettabile.

## Repair e concorrenza

Dopo un fallback riuscito, Fleet API prova a ripopolare Redis usando lo stesso
JSON di FP-030. Confronto, scrittura e TTL usano `WATCH/MULTI/EXEC` sulla stessa
connessione. Una misura più recente scritta dal processor durante il fallback
non viene sostituita da un candidato precedente. Un JSON corrotto o associato
a un altro veicolo può essere sostituito sotto `WATCH`: una modifica concorrente
fa abortire anche questo tentativo.

Il numero massimo di tentativi è configurabile, default uno. Il fallimento del
repair è osservato ma non cambia la risposta ottenuta da PostgreSQL. La risposta
descrive il sample selezionato dal fallback, non un eventuale valore più recente
scritto nel frattempo. Nessuna transazione PostgreSQL racchiude le chiamate Redis.

Le porte di lettura cache e storico sono distinte. I tipi della Fleet API e
l'adapter Redis appartengono al servizio; il contratto JSON è condiviso tramite
la specifica, senza dipendere dall'eseguibile del processor. Il DTO REST è
separato e aggiunge soltanto `stale`, senza indicare la sorgente del dato.

## Configurazione e osservabilità

- TTL repair: `fleetpulse.api.state.cache.ttl`, default `5m`, minimo `1ms`;
  usa lo stesso override `TELEMETRY_LATEST_STATE_TTL` del processor.
- Tentativi: `fleetpulse.api.state.cache.max-attempts`, default e minimo `1`.
- Timeout Redis di connessione e comando: default `500ms`, configurabili tramite
  le properties Spring. Un fallback può includere sia lettura sia repair falliti.
- Contatori Micrometer: `fleetpulse.api.cache.hits`, `.misses`, `.failures`,
  `.fallback`, `.repair.failures`. Nessun tag per veicolo o richiesta.
- `fallback` conta l'accesso al percorso PostgreSQL, anche quando non trova dati
  o fallisce. `misses` conta soltanto chiavi assenti; i guasti incrementano `failures`.
- Warning di lettura/repair limitati complessivamente a uno ogni 30 secondi per
  istanza, senza payload o stacktrace. Ogni errore incrementa comunque la metrica.

## Coerenza dopo cancellazioni: compromesso del progetto di studio

Si accetta che una chiave Redis ancora valida possa essere restituita anche se
il veicolo è stato cancellato direttamente dal database. Il progetto espone oggi
registrazione e cambio stato, non un endpoint di cancellazione. Non si introduce
un protocollo di invalidazione affidabile o un outbox per un caso d'uso non previsto.

Il TTL è `5m` dall'ultima scrittura accettata; gli hit non lo rinnovano. Senza
ulteriori scritture, una chiave orfana scompare entro il TTL residuo. Una richiesta
successiva al miss verifica PostgreSQL e risponde `VEHICLE_NOT_FOUND`.
Non si garantisce un massimo di cinque minuti dalla cancellazione: un writer o
repair già in corso può ricreare/rinnovare la chiave. Il TTL limita la vita della
singola scrittura, non realizza una cancellazione distribuita atomica.

`stale` non segnala la cancellazione del veicolo: misura solo l'età di `observedAt`.
La soglia `1m` non impedisce di restituire dati più vecchi, anche di ore.
Dopo oltre un minuto dalla misura, anche un hit relativo a un veicolo cancellato
ha `stale=true`; la cancellazione non cambia il timestamp né il flag direttamente.
La dashboard dovrà rendere visibili flag e orario della misura all'operatore:
l'API espone il segnale, ma non realizza la visualizzazione (fuori FP-031).
L'anagrafica e la telemetria hanno semantiche distinte: disabilitare un veicolo
non elimina la telemetria già persistita.

In un prodotto reale vanno concordati con il cliente il comportamento dopo
cancellazione (requisito funzionale) e il ritardo di propagazione tollerato
(requisito di qualità/coerenza). Un'eventuale cancellazione applicativa dovrà
valutare invalidazione dopo commit, guasti e scritture concorrenti. La scelta
attuale privilegia semplicità e letture indipendenti da PostgreSQL sugli hit.

## Limiti

Il percorso non riconcilia gli hit con lo storico: freshness e allineamento della
cache sono proprietà distinte. La perdita della chiave seguita da un evento
ritardato può ancora inizializzare una proiezione superata. A parità completa
di timestamp e sequenza, payload diversi rimangono equivalenti per Redis e
non si garantisce identità del payload tra cache e query PostgreSQL.

La precisione dei timestamp PostgreSQL è quella del database; il contratto non
introduce una normalizzazione aggiuntiva dei timestamp provenienti dal processor.

## Riferimenti

- [ADR-008](ADR-008-LAST-SEEN-AT-E-FRESHNESS.md)
- [ADR-009](ADR-009-LATEST-STATE-PROJECTION.md)
- [Specifica API](../09_SPECIFICA_API.md)
- [Strategia di test](../12_STRATEGIA_DI_TEST.md)
