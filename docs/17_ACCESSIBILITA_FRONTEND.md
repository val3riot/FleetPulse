# Accessibilità frontend

## Obiettivo e perimetro

Il riferimento del Fleet Dashboard è [WCAG 2.2 livello AA](https://www.w3.org/TR/WCAG22/),
come richiesto da RNF-013. Queste regole guidano implementazione e accettazione
di ogni pagina e componente. I requisiti si applicano anche a caricamento,
assenza dati, errori, operazioni in corso e dialog di conferma.

La base implementata non equivale a una certificazione: conformance e verifica
richiedono anche prove reali con browser, tastiera e tecnologie assistive.
Le pagine ancora segnaposto non sono considerate funzionalità operative complete.
Resoconti e risultati delle singole attività restano in `tmp`; questo documento
definisce i requisiti stabili.

## Requisiti verificabili

| Area              | Requisito                                                                                                                                                                      | Riferimenti WCAG           |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------- |
| Struttura         | Lingua italiana del documento, titolo specifico della pagina, un h1 e landmark main/nav/header coerenti. Tabelle con caption e intestazioni associate.                         | 1.3.1, 2.4.2, 2.4.6, 3.1.1 |
| Tastiera          | Tutte le operazioni disponibili senza mouse; ordine logico, link Vai al contenuto, nessuna trappola di focus.                                                                  | 2.1.1, 2.1.2, 2.4.1, 2.4.3 |
| Focus             | Indicatore visibile e non oscurato. Cambio pagina: focus sul main dopo il rendering. Query/filtri e ancore esplicite mantengono il comportamento contestuale.                  | 2.4.3, 2.4.7, 2.4.11       |
| Form              | Label associate ai campi, obbligatorietà esposta con required e legenda testuale, hint e messaggi collegati tramite aria-describedby.                                          | 1.3.1, 3.3.2, 4.1.2        |
| Errori            | Descrizione testuale e aria-invalid; invio non valido porta al primo campo invalido dopo il rendering. Errori generali annunciabili, valori conservati e correzione possibile. | 3.3.1, 3.3.3, 4.1.3        |
| Stato             | Caricamento, numero risultati, invio ed esito comunicati con regioni live appropriate. Lo stato non dipende soltanto dal colore.                                               | 1.4.1, 4.1.3               |
| Autoaggiornamento | Pausa/riprendi accessibili; i cicli non spostano il focus, non rimuovono il controllo focalizzato e non fanno annunciare tutta la tabella a ogni lettura.                      | 2.2.2, 2.4.3, 4.1.3        |
| Contrasto         | Testo normale almeno 4,5:1; testo grande almeno 3:1. Informazioni necessarie a identificare controlli e stati almeno 3:1 rispetto ai colori adiacenti.                         | 1.4.3, 1.4.11              |
| Reflow            | A 320 CSS px e zoom 400% nessuna perdita di contenuto o funzionalità. Testi fino ai limiti del contratto vanno a capo; tabelle possono scorrere nel proprio contenitore.       | 1.4.4, 1.4.10              |
| Spaziatura        | Aumentare interlinea e spaziatura di testo non deve troncare etichette, messaggi o azioni.                                                                                     | 1.4.12                     |
| Target            | Rispettare 24×24 CSS px o le eccezioni del criterio; preferire almeno 44×44 per pulsanti, campi e navigazione principale.                                                      | 2.5.8                      |
| Modali            | Nome e descrizione accessibili, focus iniziale appropriato, sfondo inerte e Tab/Shift+Tab contenuti. Chiusura ripristina il focus in un punto utile.                           | 2.1.1, 2.1.2, 2.4.3, 4.1.2 |

Per le conferme di cambio stato il focus iniziale è su Annulla. Esc annulla
prima dell'invio; durante la richiesta i pulsanti e la chiusura sono bloccati,
con stato di avanzamento accessibile e timeout applicativo limitato. Alla fine
il focus torna all'azione iniziale o ad Aggiorna se l'esito va verificato.

## Implementazione comune

- `src/styles.css` definisce i token della palette e l'indicatore focus-visible.
  I controlli usano i token, senza replicare colori nei componenti. Il bordo
  `--color-control-border: #66788a` ha contrasto 4,55:1 sul bianco e 4,24:1
  sullo sfondo della pagina.
- `App` gestisce il focus di navigazione tramite eventi NavigationEnd e
  afterRenderEffect. Main è focusabile programmaticamente con tabindex=-1,
  senza aggiungere una tappa all'ordine normale di Tab. Il wrapping sul
  contenuto principale preserva codici lunghi senza troncarli.
- Dopo una paginazione il focus va alla regione dei risultati; dopo una
  rilettura del dettaglio torna al titolo. Se durante l'attesa l'utente si è
  spostato a un altro controllo, il completamento HTTP non deve sottrargli il focus.
- I form usano Reactive Forms per valori e validazione. Il focus sul campo
  invalido viene applicato dopo che aria-invalid, messaggi e stato disabled
  sono stati aggiornati. Non si sposta il focus a controlli ancora disabilitati.
- I messaggi di stato hanno contenitori persistenti con role=status, aggiornati
  da signals/computed; gli errori generali usano role=alert. Non rendere live
  tutta la tabella: annunciare il riepilogo, senza rileggere ogni riga.
- Preferire HTML nativo. La conferma usa dialog/showModal per il comportamento
  modale; non basta assegnare role=dialog a un riquadro visibile nella pagina.
- Non usare il colore come unica informazione, tabindex positivo, rimozione
  dell'outline o HTML inserito direttamente dalle risposte API.

## Gate automatico

Dalla cartella frontend:

```bash
npm run verify
```

Include:

1. ESLint TypeScript e regole di accessibilità dei template Angular.
2. Prettier per il formato.
3. `npm run check:contrast`: calcolo sRGB sui token reali in styles.css.
   Il gate confronta i rapporti non arrotondati con le soglie; i valori stampati
   sono arrotondati soltanto per leggibilità. Nuovi colori o sfondi devono
   essere aggiunti alle combinazioni verificate.
4. Test comportamentali di focus su navigazione, errori locali/API e modale;
   verifica di persistenza e contenuto dei messaggi live.
5. Axe-core sui DOM delle pagine e degli stati significativi, con tag WCAG A/AA,
   più build di produzione. Le fixture HTTP non contattano il backend reale.

Axe gira in jsdom: non verifica il rendering del colore, zoom, geometria,
scroll o comportamento del dialog nativo. La regola color-contrast è quindi
esclusa da axe ed è affiancata dal gate della palette. Quest'ultimo non misura
ogni stile calcolato o futuro abbinamento di colori. Nei test del dialog
showModal/close sono simulati; trapping e backdrop si verificano nel browser.
Queste esclusioni sono limiti dello strumento, non deroghe ai requisiti.

## Storico telemetrico

- Filtri con label, asterisco e `required`, istruzioni sugli orari del database, errori associati con
  `aria-describedby` e `aria-invalid`. L'applicazione invalida porta il focus
  al primo campo errato, senza effettuare richieste.
- Regione di risultati con titolo, `aria-busy` e messaggio `role=status`
  persistente. Gli errori usano il componente comune `role=alert`; la tabella
  completa non è una regione live.
- Tabella nativa con caption, intestazioni `scope=col|row`, unità esplicite,
  `aria-sort` sulla data e date fino ai secondi, senza suffisso del fuso nel testo visibile.
- Contenitore della tabella nominato, focusabile e scorrevole orizzontalmente.
  Le righe e i singoli valori non ricevono tabindex. Il form si adatta alla
  larghezza disponibile; lo scorrimento è limitato al contenitore della tabella.
- Navigazione pagine nominata e controlli con `aria-disabled` e guardie negli
  handler. Dopo il cambio pagina il focus va alla regione dei risultati; se
  l'operatore si è spostato ai filtri durante l'attesa, il focus resta sul campo.
- Test di focus, errori e annullamento richieste, più axe su successo, vuoto,
  errore API, URL invalida e form invalido. Scroll, zoom, controlli data nativi
  e lettura con screen reader richiedono la verifica manuale sotto descritta.

## Alert

- Filtri con label visibili, istruzioni sui campi facoltativi e asterisco/required
  per size e sort; errori associati e focus al primo controllo invalido.
- Tabella con caption, scope, stato/severità testuali, data con `aria-sort` e
  contenitore scorrevole nominato e focusabile. Nessun tabindex sulle righe.
- Paginazione nominata, messaggi di caricamento/conteggio persistenti, errori
  `role=alert`; risposta di pagina porta ai risultati senza interrompere chi
  si è spostato sui filtri durante l'attesa.
- Dettaglio con titoli, lista descrittiva, date nullable esplicite e azioni
  coerenti con lo stato, mai comunicate soltanto tramite colore.
- Conferma nativa `dialog` con titolo e descrizione; focus iniziale su Annulla,
  Esc prima dell'invio, blocco durante invio e ritorno al trigger o ad Aggiorna
  stato se il comando ha rimosso il pulsante. Durante il salvataggio il focus
  resta su una descrizione accessibile e non su un controllo disabilitato.
- Successo, conflitto ed esito incerto hanno messaggi persistenti. Una rilettura
  non sottrae il focus a un controllo sul quale l'operatore si è già spostato.
- Axe su lista piena/vuota/errore/form invalido/URL invalida e dettaglio nei tre
  stati, mancante, conferma aperta e conflitto. Test HTTP coprono focus e comandi;
  dialog API simulate in jsdom, contenimento reale e zoom restano prove browser.

## Recupero da errori generali

Il riepilogo di registrazione non completata è nominato, `role=alert` e
focusabile programmaticamente con tabindex=-1. Il focus vi arriva soltanto
quando mancano errori campo e l'operatore non ha già lasciato il form; gli
errori campo conservano il percorso sul primo campo invalido. L'elenco veicoli
ripristina il punto di lettura dopo Riprova rispettando il focus spostato sui
filtri. I tentativi di recupero equivalenti già in corso non si duplicano.

Un test axe aggiuntivo copre il riepilogo generale e l'avviso di esito incerto.
I test di pagina attraversano errori sconosciuti e recupero con il client HTTP
reale e backend di test Angular. Le prove manuali usano dati isolati; non
sostituiscono la verifica con screen reader, zoom e tastiera nel browser.

## Accettazione manuale di una pagina

- Usare solo tastiera: Tab, Shift+Tab, Invio, Spazio e, per le modali, Esc.
  Verificare focus visibile, ordine, skip link, ingresso/uscita dalla modale e
  percorso completo, comprese paginazione, errore e ritorno alla pagina.
- Con VoiceOver o NVDA verificare titoli, landmark, label, required, errori
  associati, annuncio del primo campo invalido e dei messaggi live.
- Provare browser desktop con zoom 200%/400% e viewport 320 CSS px. Usare
  codici di 64 caratteri senza spazi, dati assenti, errori e testi lunghi.
  Nessuna ellissi deve rendere irrecuperabile un identificativo.
- Applicare text spacing: interlinea 1,5, spaziatura dopo paragrafi 2 volte il
  font, lettere 0,12 em, parole 0,16 em; verificare assenza di tagli e sovrapposizioni.
- Controllare dimensioni reali dei target e contrasto di tutti gli stati,
  comprese selezione, errore e focus. Verificare anche colori forzati del sistema,
  lasciando attivo il comportamento nativo del browser.

Eventuali controlli non eseguiti vanno indicati nel resoconto dell'attività;
non presentarli come superati perché lint o axe sono verdi.

## Riferimenti

- [Pausa delle informazioni autoaggiornate](https://www.w3.org/WAI/WCAG22/Understanding/pause-stop-hide.html).

- [Contrasto non testuale](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html).
- [Identificazione degli errori](https://www.w3.org/WAI/WCAG22/Understanding/error-identification.html).
- [Messaggi di stato](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html).
- [Focus non oscurato](https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html).
- [Architettura frontend](16_FRONTEND.md).
