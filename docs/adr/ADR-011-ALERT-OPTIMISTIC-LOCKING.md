# ADR-011 — Transizioni alert con optimistic locking

## Stato

Accettata nel confronto con l'utente il 2026-09-14. Implementazione e test
appartengono a FP-036.

## Contesto

Fleet API consente agli operatori di portare un alert da `OPEN` ad
`ACKNOWLEDGED` o `CLOSED` e da `ACKNOWLEDGED` a `CLOSED`. Ripetere il comando
verso lo stato corrente deve essere idempotente; una transizione inversa o non
supportata deve produrre `409 ALERT_STATUS_TRANSITION_CONFLICT`.

Due richieste possono leggere contemporaneamente la stessa versione logica
dell'alert. Un semplice read-modify-write permetterebbe all'ultima scrittura di
sovrascrivere la prima, con rischio di lost update, timestamp incoerenti e una
risposta non allineata alla macchina a stati.

Gli alert sono letti più spesso di quanto siano aggiornati e la contesa attesa è
bassa. Non è quindi necessario mantenere normalmente un lock esclusivo sulla
riga durante l'intera elaborazione del comando.

## Decisione

`maintenance_alerts` usa optimistic locking JPA. FP-036 aggiunge tramite Flyway
una colonna `version BIGINT NOT NULL DEFAULT 0`; la relativa proprietà della
entity Fleet API è annotata con `@Version`. PostgreSQL rimane l'autorità sul
confronto della versione al momento dell'update.

La matrice delle transizioni appartiene a un componente di dominio, non al
controller o al repository:

```text
OPEN         -> ACKNOWLEDGED   consentita
OPEN         -> CLOSED         consentita
ACKNOWLEDGED -> CLOSED         consentita
stesso stato -> stesso stato   idempotente
CLOSED       -> ACKNOWLEDGED   conflitto
ACKNOWLEDGED -> OPEN           non esposto e non consentito
CLOSED       -> OPEN           non esposto e non consentito
```

Il contratto REST espone come target soltanto `ACKNOWLEDGED` e `CLOSED`. `OPEN`
non è un comando valido e non viene usato per riaprire un alert.

Una transizione effettiva assegna i timestamp usando il `Clock` iniettato:

- `OPEN -> ACKNOWLEDGED` assegna `acknowledgedAt`;
- `OPEN -> CLOSED` assegna `closedAt` e lascia `acknowledgedAt` nullo;
- `ACKNOWLEDGED -> CLOSED` conserva `acknowledgedAt` e assegna `closedAt`;
- un comando idempotente non scrive e non modifica alcun timestamp.

## Gestione di un conflitto concorrente

Un fallimento optimistic indica che lo stato letto non è più autorevole. Non
viene ripetuto ciecamente lo stesso `save`: il primo tentativo viene concluso con
rollback e l'alert viene riletto in una nuova transazione.

Dopo la rilettura il comando viene rivalutato una sola volta tramite la stessa
macchina a stati:

- se il target è già raggiunto, la richiesta termina con successo idempotente;
- se la transizione è ancora consentita, viene eseguito un secondo e ultimo
  tentativo di scrittura;
- se la transizione non è più consentita, viene restituito
  `409 ALERT_STATUS_TRANSITION_CONFLICT`;
- se anche il secondo tentativo incontra una modifica concorrente, viene
  restituito lo stesso `409`, senza ulteriori retry.

Il limite comprende quindi al massimo due tentativi di scrittura e impedisce un
loop sotto contesa. Il confine transazionale deve permettere il rollback completo
del tentativo fallito prima della rilettura; non si cattura l'eccezione di
optimistic locking continuando a usare la stessa transazione marcata rollback-only.

Esempi:

- due acknowledge concorrenti producono un solo aggiornamento; la seconda
  richiesta osserva poi il target già raggiunto e ha successo;
- se acknowledge vince prima di close, close può essere rivalutata da
  `ACKNOWLEDGED` e completata;
- se close vince prima di acknowledge, acknowledge viene rivalutata da `CLOSED`
  e termina con conflitto.

## Errori e osservabilità

Un alert assente produce `404 ALERT_NOT_FOUND`. Una transizione vietata o una
contesa non risolta entro il limite produce
`409 ALERT_STATUS_TRANSITION_CONFLICT`. Gli errori infrastrutturali continuano a
seguire il classificatore REST esistente.

Log e dettagli REST non espongono query, valori interni di versione o stack
trace. L'eventuale metrica dedicata ai conflitti è rimandata alla ticket generale
sulle metriche custom e non usa `alertId` come tag.

## Conseguenze

- Gli aggiornamenti concorrenti non possono sovrascriversi silenziosamente.
- Idempotenza e validità restano proprietà della macchina a stati, non
  dell'eccezione tecnica generata da JPA.
- La tabella acquista una colonna tecnica e ogni update riuscito incrementa la
  versione.
- Il processor può continuare a inserire alert senza conoscere la versione,
  grazie al default database; la colonna non modifica l'idempotenza basata su
  `(source_message_id, type)`.
- Un client non deve inviare né conoscere `version`: il controllo resta interno
  alla Fleet API e a PostgreSQL.
- Sotto contesa ripetuta una richiesta può ricevere `409` e decidere se rileggere
  la risorsa prima di inviare un nuovo comando.

## Alternative considerate

- **Update SQL condizionale sullo stato corrente.** Evita la colonna `version` ed
  è efficiente, ma richiede query specifiche per ogni transizione e sposta parte
  della semantica nel repository. Non viene scelto per questa macchina a stati
  contenuta e gestita tramite JPA.
- **Lock pessimistico della riga.** Serializza gli operatori ma mantiene un lock
  database fino al termine della transazione. La contesa prevista non ne
  giustifica il costo e il maggior rischio di attese o deadlock.
- **Last write wins senza controllo.** È semplice ma ammette lost update e viene
  escluso.
- **Retry automatici illimitati.** Possono aumentare il carico e nascondere la
  contesa; vengono esclusi in favore di una sola rivalutazione semantica.

## Verifica richiesta

FP-036 deve dimostrare con test unitari e PostgreSQL reale:

- tutte le transizioni consentite e vietate;
- idempotenza senza variazione dei timestamp;
- incremento e controllo della versione;
- i tre esiti della rivalutazione: target già raggiunto, transizione ancora
  valida e transizione diventata invalida;
- assenza di lost update e stati impossibili con due richieste concorrenti;
- limite al secondo conflitto e mapping REST a `409`;
- contratto REST per successo, body invalido, alert assente e conflitto.

## Riferimenti

- [ADR-004 — PostgreSQL come source of truth](ADR-004-POSTGRESQL-SOURCE-OF-TRUTH.md)
- [Specifica API](../09_SPECIFICA_API.md)
- [Modello dati](../07_DATA_MODEL.md)
- [Codici errore REST](../15_CODICI_ERRORE_REST.md)
- [Strategia di test](../12_STRATEGIA_DI_TEST.md)
