# Architettura frontend

Fleet Dashboard è un client Angular standalone, zoneless, con TypeScript strict,
Reactive Forms e CSS. Comunica con Fleet API tramite REST; Grafana rimane separato.

## Struttura

| Directory | Responsabilità |
|---|---|
| `layout/` | Header e navigazione condivisi |
| `features/` | Pagine lazy per dashboard, veicoli, storico e alert |
| `core/api/` | DTO, configurazione, client HTTP e mapping errori |
| `core/polling/` | Cicli di lettura e preferenze di pausa |
| `shared/` | Errori, controlli, formatter e parser riusabili |
| `dev/` | Diagnostica esclusa dalla distribuzione |
| `testing/` | Stub e dati dei test, esclusi dalla compilazione applicativa |

`App` contiene skip link, `Header` e `main` con `RouterOutlet`. Il layout rimane
stabile nella navigazione. Stato locale con signal/computed; RxJS gestisce HTTP,
cancellazione e cleanup. Non esistono cache dati o store errori globali.

## HTTP ed errori

`FleetApi` centralizza le operazioni del [contratto REST](09_SPECIFICA_API.md).
I DTO TypeScript non validano la forma del JSON ricevuto a runtime.

- `API_CONFIG`: base `/api/v1`, timeout 10s per tentativo, un retry GET dopo 500ms.
- Retry soltanto per rete, timeout e `SERVICE_UNAVAILABLE`; mai per validazione,
  conflitto, `INTERNAL_ERROR`, codice sconosciuto o proxy HTML.
- POST/PATCH non sono mai ritentate automaticamente. Una disiscrizione annulla
  HTTP e retry pendenti, ma non garantisce rollback della scrittura server.
- Query vuote omesse, `page=0` conservato, un valore per parametro; identificativi
  codificati come singoli segmenti del path.
- `ApiError` espone codice, status, messaggio italiano, dettagli e errori campo.
  Il testo diagnostico principale del server non viene mostrato; il rendering
  usa interpolazione Angular. Codici sconosciuti ricevono un fallback sicuro.
- Rete, timeout, risorsa assente, vuoto valido e indisponibilità sono distinti.
  Un errore non diventa una collection vuota o un conteggio zero.

Errori e recupero restano nella pagina/sezione. `navigator.onLine` non dimostra
la disponibilità dell'API; un HTTP502 del proxy non è un errore di rete status 0.
Riprova non duplica una lettura equivalente pendente; nuovi filtri possono
cancellare e sostituire la ricerca. Uno snapshot mantenuto dopo errore è
identificato come dato dell'ultima lettura riuscita.

## Pagine e stato della ricerca

| Route | Comportamento |
|---|---|
| `/dashboard` | KPI e alert ordinati dal server; link a dettaglio alert e veicolo |
| `/vehicles` | Ricerca codice/targa, stato e ordinamento; paginazione server |
| `/vehicles/new` | Validazione locale e server, creazione e accesso al dettaglio |
| `/vehicles/:id` | Anagrafica, cambio stato con conferma e ultima telemetria |
| `/vehicles/:id/telemetry` | Intervallo obbligatorio, tabella, ordinamento e pagine server |
| `/alerts` | Filtri facoltativi AND, paginazione e ordinamento server |
| `/alerts/:id` | Dettaglio, presa in carico/chiusura con conferma |

Storico e alert conservano i criteri applicati nell'URL. Il form mantiene una
bozza distinta: applicare/azzerare riparte da pagina 0; paginare o riprovare usa
la ricerca applicata. I default mancanti vengono normalizzati con `replaceUrl`;
parametri invalidi o duplicati sono segnalati prima HTTP. Refresh e cronologia
ripristinano i criteri; ritorno dal dettaglio alert conserva la ricerca valida.

Lo storico fissa le ultime 24 ore al primo accesso senza intervallo; non sposta
la fine durante refresh o paginazione. Da/A sono inclusivi, uguali ammessi,
nessun limite aggiuntivo alla durata. Negli alert ciascun estremo è facoltativo.
Size 1..100 e sort sono verificati localmente ed eseguiti dal server; nessun
riordino o aggregazione locale. Pagina oltre ultima e intervallo vuoto sono distinti.

Gli orari seguono UTC come il database di riferimento. Date visibili in formato
`dd/MM/yyyy HH:mm:ss`, senza suffisso; input fino ai secondi. URL e richieste
conservano la precisione originale finché il campo non cambia. Il parser
confronta anche frazioni sotto il millisecondo, senza conversione nel fuso browser.

## Scritture

La registrazione invia soltanto i quattro campi previsti, senza status. Limiti:
codice: 64, targa: 16 caratteri; intervallo manutenzione: 1..2147483647 km;
prossima manutenzione: 0..Number.MAX_SAFE_INTEGER km. Numeri interi sicuri e
stringhe non vuote; messaggi campo senza perdere i valori del form.

Cambio stato veicolo e comandi alert usano un `dialog` nativo. Durante l'invio
sono impediti doppio comando e chiusura della conferma. Lo stato cambia solo
con il DTO restituito, senza aggiornamenti ottimistici.

- Alert OPEN: presa in carico o chiusura; ACKNOWLEDGED: chiusura; CLOSED: nessuna
  modifica. I timestamp nullable restano assenze esplicite; non c'è riapertura.
- Conflitto alert 409: chiusura dialog e GET dello stato; comandi sospesi fino
  al successo della rilettura, senza ripetere PATCH o riaprire la conferma.
- Rete/timeout/5xx in scrittura: esito incerto, verifica obbligatoria prima di
  un nuovo comando. Nella registrazione si cerca codice/targa nell'elenco;
  nei dettagli si aggiorna lo stato. Il ritorno della rete non reinvia modifiche.

## Polling

Solo dashboard e ultima telemetria usano `createPollingResource`. `POLLING_CONFIG`
definisce 5000ms di attesa dopo la conclusione della lettura, inclusi retry;
nessuna sovrapposizione. L'intervallo deve essere un intero 1..2147483647ms.

Prima lettura immediata se visibile e abilitata. Pausa impedisce nuovi cicli,
lasciando terminare la richiesta corrente; Aggiorna resta disponibile. Riprendi
legge subito quando possibile. Scheda nascosta e conferma del cambio stato
sospendono nuovi cicli. Uscita/cambio identità cancellano HTTP, timer e listener.

Le pause sono conservate in memoria durante la sessione, separatamente per
dashboard e telemetria; nessun localStorage/sessionStorage o snapshot condiviso.
Al rientro è prevista una lettura iniziale anche in pausa, poi nessun ciclo.

Errore temporaneo conserva snapshot e ultima verifica; errore terminale ferma
l'automatismo. Assenza telemetria continua a essere verificata. Una risposta
successiva risolve l'errore anche con dati identici. La dashboard mantiene i
riferimenti dati/KPI/alert se DTO e ordine non cambiano; avanza soltanto la data
di verifica. Nessun paragrafo transitorio di loading dopo il primo successo.

## Distribuzione e verifiche

La build produzione sostituisce le rotte dev con una lista vuota. Metadati di
build fuori da `browser/`; il gate controlla grafo moduli, entrypoint, chunk,
risorse HTML e assenza di diagnostica/fixture nella distribuzione.
La fixture UI vive in un processo locale separato, senza inoltro al backend.

Comandi e proxy: [README frontend](../frontend/fleet-dashboard/README.md).
Test: [strategia](12_STRATEGIA_DI_TEST.md). Focus, semantica e requisiti visivi:
[accessibilità](17_ACCESSIBILITA_FRONTEND.md).
