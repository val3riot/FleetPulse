# ADR-009 — Contratto e aggiornamento della latest-state projection

## Stato

Accettata il 2026-09-08. Implementazione prevista in FP-030; integrazione del
fallback e del repair in FP-031. Lo stato accettato riguarda la progettazione,
non attesta il completamento del codice.

## Contesto

Redis conserva una proiezione ricostruibile della telemetria PostgreSQL.
Eventi in ritardo, reset della sequenza al riavvio e scritture concorrenti
richiedono un criterio esplicito di aggiornamento. Occorre inoltre definire
porta applicativa, durata della cache e osservabilità senza accoppiare il
servizio applicativo a Redis.

## Decisioni

## 1. Porta applicativa

Il servizio applicativo dipende da una porta `LatestStateProjection` con il
contratto `ProjectionUpdateResult updateIfNewer(LatestVehicleState candidate)`.
Porta e tipi applicativi non dipendono da `RedisTemplate`, serializer o eccezioni
Redis. L'adapter gestisce questi dettagli e la conversione nel DTO Redis.

`LatestVehicleState` rappresenta i dati della misura candidata alla proiezione.
`ProjectionUpdateResult` distingue `UPDATED` e `SKIPPED`. Un errore
infrastrutturale viene tradotto in un'eccezione applicativa dedicata, gestita
dall'orchestratore dopo il commit. La porta di lettura per FP-031 è distinta.

Nel codice attuale la porta è abbozzata nel package `telemetry.redis`; i tipi
`LatestVehicleState` e `ProjectionUpdateResult` devono ancora essere creati.
La collocazione della porta dovrà riflettere il suo ruolo applicativo.

## 2. Key e DTO Redis

Contratto di riferimento: [modello dati, sezione 4](../07_DATA_MODEL.md)
e [ADR-008](ADR-008-LAST-SEEN-AT-E-FRESHNESS.md).

Key esatta: `vehicle:last:{vehicleId}`, sostituendo il segnaposto con l'UUID
canonico del veicolo. La costruzione della key appartiene all'adapter.

Il DTO Redis usa JSON con questi campi:

| Campo | Tipo Java | Rappresentazione JSON |
|---|---|---|
| `vehicleId` | `UUID` | stringa UUID |
| `lastSequenceNumber` | `long` | intero |
| `lastSeenAt` | `Instant` | stringa ISO-8601 UTC |
| `speedKmh` | `double` | numero |
| `engineTemperatureC` | `double` | numero |
| `batteryVoltage` | `double` | numero |
| `odometerKm` | `long` | intero |
| `latitude` | `double` | numero |
| `longitude` | `double` | numero |

`lastSequenceNumber` deriva da `sequenceNumber`; `lastSeenAt` deriva da
`observedAt`, mai dal momento di scrittura Redis. `stale` appartiene alla
risposta API e non al valore Redis. Il DTO Redis è separato da entity JPA e DTO REST.

## 3. Recency e atomicità

Questo ADR completa il criterio di ordinamento lasciato aperto da ADR-008.

Si confronta la coppia `(observedAt, sequenceNumber)` in ordine lessicografico:

1. Vince il timestamp di osservazione più recente.
2. A parità di timestamp, vince la sequenza maggiore.
3. A parità di entrambi, nessun aggiornamento (`SKIPPED`).

Una chiave assente può essere inizializzata dal candidato. Confronto,
scrittura e impostazione del TTL devono costituire un'operazione atomica:
una lettura seguita da una scrittura indipendente non protegge dalla concorrenza.
La scelta della tecnica Redis resta un dettaglio di implementazione.

Fallback e ricostruzione PostgreSQL devono usare lo stesso criterio di recency.
Il reset della sequenza dopo un riavvio non impedisce di accettare una misura
con timestamp più recente; un evento vecchio arrivato in ritardo non la sostituisce.

Limiti da mantenere espliciti:

- Si assume un orologio del dispositivo affidabile; non è stata concordata una
  nuova policy per clock skew o timestamp futuri.
- Misure con stessa coppia ma payload diversi sono equivalenti per questo
  confronto. La selezione deterministica tra tali righe nel fallback deve essere
  precisata in FP-031; non è garantita l'identità del payload tra i percorsi.
- Dopo scadenza o perdita della chiave manca il precedente termine di confronto:
  un evento ritardato può inizializzarla. Il TTL non garantisce che la cache
  contenga il massimo storico PostgreSQL; il tema resta nel percorso di repair.

## 4. TTL tramite properties

Contratto di riferimento: TTL configurabile e distinto dalla freshness, in modello dati
e ADR-008.

- Property `fleetpulse.telemetry.latest-state.ttl`, di tipo `Duration`.
- Valore iniziale di default `5m`, sovrascrivibile da configurazione.
- Validazione all'avvio: valore non nullo e almeno `1ms`; configurazioni non
  valide impediscono l'avvio. La conversione deve rispettare la precisione Redis.
- TTL applicato all'inserimento e rinnovato solo per uno stato accettato.
- Eventi vecchi o equivalenti non rinnovano il TTL.
- Scrittura e TTL sono atomici insieme al confronto di recency.

Il valore `5m` è una scelta iniziale, non una soglia di freshness per l'API.

## 5. Confine transazionale

Contratto di riferimento: [modello dati, sezione 3](../07_DATA_MODEL.md)
e [ADR-005](ADR-005-REDIS-CACHE-RICOSTRUIBILE.md).

PostgreSQL è autorevole; sample e alert derivati appartengono alla stessa
transazione. Redis viene aggiornato dopo il commit e un suo errore non lo annulla.

l'orchestratore chiama il componente transazionale PostgreSQL; al ritorno dopo
commit chiama la porta di proiezione. L'orchestratore non avvolge entrambe le
operazioni in una transazione PostgreSQL.

Duplicate e rejection non generano update.
Il fallimento Redis viene gestito senza rilanciarlo verso Kafka dopo il commit.
Il recupero delle letture tramite fallback appartiene a FP-031.

## 6. Log e metriche bounded

Contratto di riferimento: [osservabilità](../11_OBSERVABILITY.md) richiede segnali
di aggiornamento Redis e include `fleetpulse_redis_update_failures_total`.

| Esito | Significato | Livello log |
|---|---|---|
| `UPDATED` | Stato scritto e TTL impostato | `DEBUG` |
| `SKIPPED` | Candidato precedente o equivalente | `DEBUG` |
| `FAILED` | Operazione Redis fallita | `WARN` |

Contatore applicativo: `fleetpulse.telemetry.latest_state.updates`, con il solo
tag `outcome` e valori fissi `updated`, `skipped`, `failed`. Ogni tentativo
produce un solo esito; `FAILED` è osservato dall'orchestratore quando gestisce
l'eccezione applicativa, non è un valore di `ProjectionUpdateResult`.

`vehicleId`, `messageId` e sequenza sono ammessi nei log, mai nei tag delle
metriche. Niente payload completo nei log. Durante guasti prolungati i warning
sono limitati in frequenza, mentre il contatore registra tutti i fallimenti.
Intervallo e meccanismo del limite dei log restano dettagli da fissare in implementazione.

Il contatore affianca `fleetpulse_redis_update_failures_total`, che resta nel
catalogo: ogni fallimento incrementa anche questa metrica, senza tag dinamici.

## Alternative considerate

- Confrontare solo `sequenceNumber`: il reset dopo riavvio impedisce di
  riconoscere correttamente le nuove misure.
- Attendere il TTL per accettare una sequenza bassa: la scadenza non ordina
  gli eventi e non impedisce l'arrivo successivo di una misura vecchia.
- Confrontare solo `observedAt`: manca il criterio secondario concordato
  per timestamp coincidenti.
- Leggere e poi scrivere senza atomicità: una race può far regredire lo stato.
- Includere Redis nella transazione PostgreSQL: non offre atomicità tra i due
  sistemi e accoppia la persistenza autorevole alla disponibilità della cache.

## Verifiche previste

- Mapping JSON, key e significato dei timestamp conformi al modello dati.
- Misure più recenti accettate, precedenti ed equivalenti ignorate.
- Reset della sequenza e arrivo tardivo di una misura precedente al riavvio.
- Scritture concorrenti senza regressione della coppia di recency.
- TTL reale applicato solo agli update accettati; configurazione invalida
  rifiutata all'avvio.
- Nessun update prima del commit o dopo duplicate/rejection.
- Errore Redis dopo commit osservabile senza rollback o retry Kafka.
- Esiti metrici a cardinalità limitata e limitazione dei warning durante guasti.

## Riferimenti

- [Modello dati](../07_DATA_MODEL.md)
- [Specifica API](../09_SPECIFICA_API.md)
- [Osservabilità](../11_OBSERVABILITY.md)
- [Configurazione e deployment](../13_DEPLOYMENT.md)
- [ADR-005](ADR-005-REDIS-CACHE-RICOSTRUIBILE.md)
- [ADR-008](ADR-008-LAST-SEEN-AT-E-FRESHNESS.md)
- FP-030 e FP-031.
