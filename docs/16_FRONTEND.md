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

## Client REST

`src/app/core/api/fleet-api.ts` centralizza le 11 operazioni REST: veicoli,
stato corrente, dashboard, storico, alert e relative scritture. I DTO sono in
`api-models.ts`. I timestamp restano stringhe UTC del contratto; la presentazione
in fuso locale sarà responsabilità delle pagine. La tipizzazione non sostituisce
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
