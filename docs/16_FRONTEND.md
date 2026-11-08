# Architettura frontend

Fleet Dashboard comunica esclusivamente con Fleet API tramite REST HTTP/JSON.
Grafana rimane separato ed espone l’osservabilità tecnica.

## Struttura e layout

`App` contiene la struttura comune: collegamento al contenuto, `Header` e
`main` con `RouterOutlet`. L'header è isolato in `src/app/layout/header/` con
component, template e stile dedicati. Tutte le rotte attuali, inclusa la pagina
non trovata, usano questa struttura senza ricrearla durante la navigazione.
Anche lista, form e dettaglio previsti possono usare lo stesso contenitore;
i contenuti e le azioni specifiche appartengono alle singole pagine.

La struttura del codice è organizzata per responsabilità:

- `src/app/layout/`: componenti della struttura condivisa.
- `src/app/features/`: pagine organizzate per funzionalità.
- `src/app/core/api/`: configurazione, DTO, client REST e normalizzazione errori.
- `src/app/shared/`: componenti condivisi, inclusi messaggi di errore e pagina non trovata.
- `src/app/dev/`: diagnostica disponibile solo in sviluppo.

## Accessibilità condivisa

La base applicativa segue [i requisiti WCAG 2.2 AA](17_ACCESSIBILITA_FRONTEND.md).
Il layout gestisce il focus dopo un cambio pagina, mantenendo query e ancore
nel loro contesto. I form portano il focus al primo campo invalido dopo il
rendering e i riepiloghi di stato rimangono nel DOM per gli annunci assistivi.
Paginazione e aggiornamento del dettaglio ripristinano un punto di lettura utile,
senza sottrarre il focus a chi si è già spostato durante l’attesa HTTP.
Palette e focus sono definiti nei token CSS globali; i contenuti variabili
vanno a capo anche con identificativi lunghi. Il gate include contrasto dei
token, semantica axe e test dei comportamenti di focus.

## Client REST

`src/app/core/api/fleet-api.ts` centralizza le 11 operazioni REST: veicoli,
stato corrente, dashboard, storico, alert e relative scritture. I DTO sono in
`api-models.ts`. I timestamp restano stringhe UTC del contratto; la presentazione
è responsabilità delle pagine; dashboard, telemetria e alert usano UTC
internamente e mostrano le date senza suffisso del fuso. La tipizzazione non sostituisce
una validazione runtime delle risposte di successo.

## Configurazione API

`src/app/core/api/api-config.ts` contiene i default:

- base URL `/api/v1` (comprende già il prefisso API);
- un solo retry per lettura, dopo 500 ms;
- timeout di 10 secondi per tentativo.

La configurazione è iniettata tramite `API_CONFIG`: può essere sostituita nei
provider di `app.config.ts` con `{ provide: API_CONFIG, useValue: { ...DEFAULT_API_CONFIG,
baseUrl: 'https://host/api/v1' } }`, importando entrambi da `./core/api/api-config`.
Un'origine diversa richiede che il backend autorizzi CORS. La configurazione runtime del container non è ancora implementata.

Le query omettono valori null/undefined o stringhe vuote, conservano `page=0`
e inviano un solo valore per parametro. Gli identificativi sono codificati
come singoli segmenti del percorso.

## Errori e retry

`ApiError` espone `code`, `status`, messaggio italiano, `retryable`, `details`
e `fieldErrors` (mappa campo → messaggi). Per esempio, il futuro form veicolo
può usare `error.fieldErrors.get('plate')`. `REQUEST_INVALID` conserva i dettagli
validi; i conflitti di targa/codice esterno vengono associati al rispettivo campo.
Gli altri errori restano generali. `ApiErrorNotice` visualizza messaggio e dettagli
tramite interpolazione Angular, senza inserire HTML dalla risposta.

Il testo diagnostico principale del backend non viene mostrato. Codici sconosciuti
e risposte non JSON hanno un messaggio generico per famiglia HTTP. Sono distinti
rete, timeout, assenza veicolo e assenza telemetria.

Le GET ritentano soltanto rete, timeout e `SERVICE_UNAVAILABLE`, entro il limite
configurato. Non vengono ritentati automaticamente errori di validazione,
conflitti, `INTERNAL_ERROR` o codici sconosciuti. POST/PATCH non vengono mai
ritentati automaticamente, neanche dopo timeout: l'esito della scrittura può
essere ambiguo e va verificato prima di un nuovo invio. La disiscrizione annulla
richieste e retry in attesa.

## Pagine veicoli

Le rotte `/vehicles`, `/vehicles/new` e `/vehicles/:id` caricano componenti
standalone dedicati e condividono il layout applicativo. Usano form reattivi
Angular, signals per lo stato locale e il client REST comune.

L'elenco applica ricerca per codice/targa, stato e ordinamento su invio esplicito
del form. La paginazione è server-side, con 20 elementi per pagina. Cambiare i
filtri riporta alla prima pagina; una nuova ricerca annulla la precedente.
I filtri sono locali alla pagina e si azzerano lasciandola. Loading, pagina
vuota ed errore sono distinti; un errore non viene rappresentato come zero risultati.

La registrazione invia soltanto i quattro campi del contratto, senza `status`.
Valida campi obbligatori, lunghezze, interi e limiti numerici, conserva i valori
in caso di errore e associa validazione e conflitti ai rispettivi campi.
L'intervallo manutenzione rispetta il limite Integer del backend; la prossima
manutenzione è limitata al massimo intero sicuro JavaScript per evitare perdita
di precisione. Il successo apre il dettaglio del veicolo restituito dal server.

Il dettaglio separa l’anagrafica e lo stato amministrativo dalla sezione
dell’ultima telemetria, che si aggiorna autonomamente. Il cambio ACTIVE/DISABLED richiede conferma in una modale nativa `<dialog>`
aperta con `showModal()` e aggiorna lo stato mostrato solo dopo il successo della PATCH. Un cambio di identificativo
annulla le richieste della precedente pagina. Le date mostrano il fuso locale
con offset esplicito.

Gli invii concorrenti sono bloccati. Dopo errori di rete, timeout o HTTP 5xx su
scrittura, l'esito è considerato incerto: per una registrazione si verifica
l'elenco per codice/targa prima di riaprire il form; per un cambio stato si
rilegge il dettaglio prima di consentire un'altra modifica.

La modale ha titolo e descrizione accessibili, sfondo oscurato e focus iniziale
su Annulla. Il dialog nativo rende inerte il resto della pagina e contiene la
navigazione da tastiera. Esc annulla prima dell'invio; durante la PATCH non può
chiudere la modale e i pulsanti sono disabilitati. Alla chiusura il focus torna
all'azione iniziale oppure ad Aggiorna se lo stato deve essere verificato.
L'apertura/chiusura DOM è sincronizzata con i signals tramite `afterRenderEffect`.
Non richiede librerie UI aggiuntive. Riferimenti:
[dialog HTML](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/dialog),
[afterRenderEffect](https://angular.dev/api/core/afterRenderEffect).

### Stato reattivo e validazione

I signals rappresentano lo stato letto dai template: caricamento, errori,
risultati, invio in corso ed esito incerto. Non esiste uno store globale.
Reactive Forms gestisce valori, touched, disabled e validazione dei campi.
RxJS coordina HTTP e cancellazione; nell'elenco un BehaviorSubject conserva i
filtri applicati e switchMap annulla la lettura superata. I risultati HTTP
aggiornano i signal, che notificano i template anche in modalità zoneless.

`vehicle-limits.ts` centralizza i limiti del form, condivisi fra validatori e
attributi HTML: codice 64 caratteri, targa 16, intervallo da 1 a 2147483647 km,
prossima manutenzione da 0 al massimo intero sicuro JavaScript. I primi due
limiti sono coerenti con il contratto REST e le colonne VARCHAR del database.
I massimi numerici sono tecnici; eventuali limiti operativi più stretti richiedono
una regola di dominio condivisa anche dal backend. I campi obbligatori sono
indicati con asterisco, legenda e attributo required.

## Dashboard e ultima telemetria

La dashboard legge una singola risorsa aggregata da `GET /dashboard`: totale,
ACTIVE/DISABLED, veicoli con rilevazioni recenti, conteggio OPEN e alert rilevanti.
I conteggi non vengono ricostruiti sul client. Il KPI delle rilevazioni recenti
può includere DISABLED e non è una misura di veicoli online. La finestra temporale
rimane configurata sul backend, senza un valore fisso dichiarato nel template.

Gli alert mantengono l'ordine del backend e mostrano tipo, descrizione, severità,
stato, data e collegamento al veicolo. Il contratto non espone codice/targa:
si usa vehicleId senza richieste per riga o estensioni implicite del DTO. La UI
non introduce collegamenti a un dettaglio alert ancora non implementato.

`VehicleState` è una sezione standalone distinta dall'anagrafica. Mostra velocità,
temperatura, batteria, odometro e coordinate con unità e formattazione italiana.
La freshness segue il flag stale del backend; ACTIVE/DISABLED rimane un'altra
informazione. lastSeenAt identifica la misura, non il momento della lettura HTTP.

`VEHICLE_STATE_NOT_AVAILABLE` rappresenta assenza di telemetria: i valori non
vengono sostituiti con zeri e le verifiche periodiche continuano. `VEHICLE_NOT_FOUND`
cancella il dato della sezione e interrompe il ciclo. L'errore della sola sezione
telemetrica non blocca l'anagrafica o il cambio stato.

## Polling e conservazione dell'ultimo risultato

`createPollingResource` in `core/polling/polling-resource.ts` crea una risorsa locale per ogni componente.
Non è uno store globale né una cache fra pagine. Espone signals in sola lettura
per dati, errore, richiesta in corso, pausa e ultimo successo; i riepiloghi della
UI usano computed. L'effetto Angular collega identità/abilitazione alle API
imperative HTTP e timer, senza tracciare come dipendenze i risultati delle letture.

`POLLING_CONFIG` in `core/polling/polling-config.ts` definisce intervalMs, default 5000. Il valore deve essere un intero fra 1 e 2147483647 ms, entro il range dei
timer JavaScript. Può essere sostituito nei provider applicativi o nei test.

- Prima lettura immediata se la sezione è visibile e abilitata. Ogni successiva
  attende l'intervallo configurato dopo la conclusione della lettura precedente,
  inclusi i retry HTTP. Non vengono accumulati tick o richieste.
- Il client FleetApi conserva la responsabilità di timeout e retry per lettura;
  il polling non aggiunge ulteriori retry alla stessa richiesta.
- La sola preferenza di pausa è conservata in memoria da
  PollingSessionPreferences, separata negli ambiti dashboard e telemetry.
  Rimane durante navigazione e cambi di veicolo; una nuova istanza dell'app
  riparte attiva. Non usa localStorage/sessionStorage né conserva snapshot HTTP.
- Entrando in una pagina visibile e abilitata viene letta una singola fotografia
  anche se la preferenza è in pausa: evita una pagina senza dati al rientro.
  Il polling periodico rimane fermo finché l'operatore non sceglie Riprendi.
- Pausa blocca la pianificazione, lasciando terminare una lettura in corso.
  Aggiorna resta utilizzabile in pausa. Riprendi rilegge subito quando possibile.
- La scheda nascosta sospende nuovi cicli; tornare visibili rilegge subito se
  non esiste una lettura in corso e l'utente non ha messo in pausa.
- La conferma di cambio stato sospende la sezione telemetrica. Chiusura della
  conferma e cambio di identità sono gestiti senza sovrapposizioni; cambiare
  veicolo cancella richiesta, timer e dati della precedente identità.
- Uscire dalla pagina cancella timer, richiesta e listener visibilitychange.
- Gli errori temporanei conservano ultimo snapshot e data del precedente successo;
  la risposta successiva può recuperare automaticamente. Una verifica fallita
  non aggiorna la data dell'ultimo successo né rende attuali i dati conservati.
- Errori non retryable, incluso INTERNAL_ERROR e risposte proxy/codici sconosciuti,
  fermano l'automatismo per evitare loop; l'utente può riprovare esplicitamente.
  L'assenza di telemetria è l'eccezione di dominio che continua a essere verificata.

La dashboard confronta i valori del DTO, inclusi tutti i campi e l'ordine degli
alert. Una risposta identica mantiene il riferimento del signal dati e delle
proiezioni KPI/alert; un cambiamento effettivo viene pubblicato. Il confronto è
configurabile sulla risorsa, senza cache globale. Successo, errori e data di
verifica restano indipendenti: anche una risposta identica aggiorna l'ultima
lettura riuscita e cancella un errore precedente. Il testo di caricamento è
visibile soltanto prima del primo successo, evitando salti del layout ai cicli
in background. Richieste e attesa di cinque secondi rimangono invariate.

I controlli di aggiornamento rimangono nel DOM. Quando una lettura è in corso,
aria-disabled indica l'indisponibilità del refresh e l'handler impedisce un
secondo invio, mantenendo il pulsante focusabile. I cicli non spostano il focus,
non smontano i dati validi e non rendono live l'intera tabella o tutti i valori.
I messaggi persistenti annunciano primo caricamento, errore, disponibilità dopo
recupero e variazioni della pausa; non viene letto un timestamp a ogni ciclo.
In pausa la freshness mostrata si riferisce esplicitamente all'ultima verifica.

## Storico telemetrico

La pagina lazy `/vehicles/:id/telemetry` è raggiungibile dal dettaglio e usa il
layout comune. Presenta una tabella, senza grafici né polling. L'anagrafica è
letta una volta per il contesto corrente; le pagine successive interrogano solo
`FleetApi.getTelemetry`. Cambiare veicolo cancella richieste e dati precedenti.

- `from` e `to` sono obbligatori e inclusivi su `observedAt`; gli istanti uguali
  sono ammessi. Non è imposto un limite aggiuntivo alla durata del range.
- Alla prima apertura senza intervallo vengono fissate le ultime 24 ore, una
  sola volta. I default sono pagina 0, dimensione 50 e data decrescente.
- Filtri applicati, pagina, dimensione e ordinamento sono nell'URL. La prima
  normalizzazione usa `replaceUrl`; refresh e navigazione della cronologia
  ripristinano gli stessi criteri. Parametri invalidi o duplicati vengono
  segnalati senza chiamate HTTP o sostituzioni silenziose.
- Il form mantiene una bozza distinta dalla ricerca applicata. Applica filtri
  riparte da pagina 0; la paginazione usa i criteri applicati. Aggiorna risultati
  e Riprova rileggono l'intervallo fisso, senza spostarne la fine.
- Date di filtro e tabella sono interpretate in UTC, coerenti con il database
  dell'ambiente di riferimento (`TimeZone=UTC`, `observed_at` di tipo
  `timestamp with time zone`). PostgreSQL memorizza istanti: il suo fuso di
  sessione regola la visualizzazione, non aggiunge un fuso al dato.
- I controlli nativi consentono secondi e vengono interpretati in UTC,
  senza conversioni nel fuso del browser. Gli istanti originali dell'URL sono
  preservati se il campo non cambia, anche con micro/nanosecondi; confronto del
  range e richieste mantengono la precisione originale. Date e orari visibili
  usano il formato `dd/MM/yyyy HH:mm:ss`, senza suffisso del fuso né frazioni di secondo,
  condiviso con dashboard e ultima telemetria.
  Non viene estesa implicitamente la fine dell'intervallo.
- Dimensione pagina da 1 a 100; ordinamento solo `observedAt,asc|desc`.
  Conteggi, ordine e pagine sono forniti dal server, incluso il tie-breaker su
  `id`; il frontend non riordina né aggrega i risultati. Le unità sono nelle
  intestazioni e la formattazione numerica è condivisa con l'ultima telemetria.
- `switchMap` cancella letture superate; la distruzione del componente annulla
  la richiesta. Una nuova ricerca nasconde il risultato precedente. Vuoto
  valido, pagina oltre l'ultima, veicolo assente ed errore restano distinti.
  Riprova usa il mapping errori, timeout e retry del client comune.

Lo storico non modifica le preferenze di pausa della dashboard/ultima telemetria.
Le pagine del backend non costituiscono uno snapshot transazionale: nuovi dati
possono cambiare conteggi o distribuzione delle righe fra richieste distinte.

## Consultazione e gestione degli alert

`/alerts` e `/alerts/:id` sono pagine standalone lazy nello stesso layout.
Lista e dettaglio leggono all'ingresso e su richiesta manuale, senza polling;
le preferenze di pausa dashboard/telemetria restano indipendenti. Dashboard e
dettaglio veicolo collegano rispettivamente il dettaglio alert e la lista con
`vehicleId` precompilato. Il link al veicolo resta disponibile nelle righe.

L'elenco usa `FleetApi.getAlerts`, senza letture anagrafiche per riga. Filtri
opzionali combinati AND: UUID veicolo, stato, tipo, severità, Da e A su
`createdAt`. Ciascuna data può essere usata da sola; nessun intervallo è imposto
quando entrambe sono assenti. Limiti inclusivi, uguaglianza ammessa e rifiuto
locale di un intervallo inverso, senza introdurre altre restrizioni temporali.

- Default tutti gli stati e nessun filtro, pagina 0, size 50, data decrescente.
  Size da 1 a 100; sort solo `createdAt,asc|desc`, eseguito dal server.
- Filtri applicati e paginazione sono nell'URL, con normalizzazione `replaceUrl`
  dei default mancanti. Il dettaglio conserva i parametri validi nel link di
  ritorno alla lista, senza URL di ritorno esterni o stato globale.
- Applicare/azzerare riparte da pagina 0. Le modifiche in bozza non generano
  HTTP; paginazione e refresh usano i criteri applicati. URL invalida o con
  parametri duplicati viene segnalata prima della richiesta, senza correzioni
  silenziose. Il form consente una nuova ricerca valida.
- Date/formato riusano i controlli e formatter condivisi con lo storico:
  immissione fino ai secondi e conservazione di eventuale precisione superiore
  dei parametri originali non modificati. Nessuna conversione nel fuso browser.
- L'UUID filtro senza corrispondenze produce una lista vuota secondo l'API
  globale. Vuoto, pagina oltre ultima ed errore rimangono distinti.
- Il dettaglio legge `getAlert`; i timestamp nullable restano assenze esplicite.
  Etichette di tipo, stato e severità sono condivise con la dashboard.

Le azioni risiedono nel dettaglio e richiedono conferma con dialog nativo:
`OPEN` consente presa in carico o chiusura, `ACKNOWLEDGED` soltanto chiusura,
`CLOSED` nessuna modifica. Non è prevista riapertura. Durante la scrittura si
impediscono doppio invio e chiusura del dialog. Lo stato visibile cambia solo
con il DTO restituito dalla PATCH, senza aggiornamenti ottimistici.

Un `409 ALERT_STATUS_TRANSITION_CONFLICT` chiude la conferma, mantiene il
messaggio e avvia una GET dello stato corrente; i comandi restano sospesi fino
alla rilettura riuscita. Non si reinvia automaticamente la PATCH e non si
riapre la conferma. Un errore della rilettura consente il recupero manuale.
Rete, timeout o 5xx in scrittura sono esiti incerti: nuovi comandi sono bloccati
finché l'operatore non aggiorna lo stato con successo. `404 ALERT_NOT_FOUND`
rimuove le azioni e resta distinto dalla lista vuota. I dati conservati dopo
un errore di lettura sono indicati come appartenenti all'ultimo successo.

Letture superate, cambio identità e distruzione cancellano le sottoscrizioni.
Annullare un'operazione HTTP non garantisce rollback server: una nuova visita
rilegge sempre lo stato. La semantica di concorrenza resta quella dell'API e di
[ADR-011](adr/ADR-011-ALERT-OPTIMISTIC-LOCKING.md), senza versioni JPA nel frontend.

## Diagnostica di sviluppo

La rotta `/dev/api` esegue soltanto letture di dashboard e veicoli tramite lo
stesso client applicativo. La build produzione sostituisce `dev.routes.ts` con
`dev.routes.production.ts`, che esporta una lista vuota: rotta e componente
diagnostico sono esclusi dalla distribuzione.

La fixture locale è separata dal backend reale. Comandi e proxy di sviluppo
sono descritti nel [README frontend](../frontend/fleet-dashboard/README.md).

## Dipendenze e override

Le dipendenze e il package manager sono dichiarati in `package.json`; il lockfile
è versionato e l'installazione ripetibile usa `npm ci`.

L'override `minimatch@>=7 <11` → `11.0.5` corregge il range vulnerabile descritto
in [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249).
È necessario finché ESLint e TypeScript ESLint dichiarano ancora la major 10.
L'override attraversa una major: quando i consumatori supporteranno una versione
corretta, rimuoverlo, rigenerare il lockfile e ripetere lint, test, build e audit.

Il controllo dipendenze si esegue con `npm run audit`, separatamente dal gate
funzionale `npm run verify`. L'audit consulta il database corrente; i resoconti
delle singole esecuzioni sono conservati in `tmp`.

## Riferimenti

- [Specifica REST](09_SPECIFICA_API.md).
- [Catalogo errori REST](15_CODICI_ERRORE_REST.md).
- [Testing HTTP Angular](https://angular.dev/guide/http/testing).
