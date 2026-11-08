# Accessibilità frontend

Riferimento: [WCAG 2.2 livello AA](https://www.w3.org/TR/WCAG22/), secondo RNF-013.
I requisiti si applicano anche a loading, vuoti, errori e scritture pendenti.

## Struttura e navigazione

- Lingua italiana, titolo pagina, un `main`, gerarchia heading coerente, navigazione
  nominata e skip link visibile al focus. Header stabile tra le pagine.
- Dopo cambio pagina il focus va al contenuto; query e ancore esplicite conservano
  il contesto. Nessun tabindex sui testi statici o sulle righe delle tabelle.
- Paginazione e recupero portano a un punto di lettura utile se il controllo è
  scomparso, senza interrompere chi si è già spostato sui filtri durante HTTP.

## Form e messaggi

- Label visibili, required semantico, asterisco con legenda e limiti comprensibili.
  Hint/errori collegati con `aria-describedby`; `aria-invalid` solo quando utile.
- Validazione locale prima HTTP e focus al primo campo invalido dopo il rendering.
  Per errore generale di registrazione: riepilogo nominato, `role=alert` e
  tabindex=-1; focus solo se l'operatore non ha già lasciato il form.
- Messaggi `role=status` persistenti per caricamento/esito; errori con `role=alert`.
  Non rendere live tutta la tabella e non annunciare timestamp a ogni polling.
- Errori, severità, stato e snapshot precedenti sono comprensibili senza colore.
  L'esito incerto indica un percorso di verifica; non comunica falso successo.
- `aria-disabled` mantiene il controllo raggiungibile quando opportuno; l'handler
  deve impedirne realmente l'azione. Nessuna richiesta duplicata o focus perso
  per un aggiornamento periodico privo di cambiamenti dei dati.

## Tabelle e dialog

Tabelle native con caption, `th`/scope e `aria-sort` sulla colonna ordinata.
Unità nelle intestazioni, date leggibili fino ai secondi e identificativi integri.
Contenitore scorrevole nominato e focusabile, con istruzioni da tastiera.

Conferme con `dialog` nativo, titolo/descrizione associati e focus iniziale su
Annulla. Esc chiude prima dell'invio; durante scrittura chiusura e doppio invio
sono impediti e la descrizione di avanzamento riceve focus. Alla chiusura il
focus torna al trigger ancora valido o a un controllo di aggiornamento.
Il contenimento nativo del focus deve essere provato nel browser.

## Aspetto e controlli automatici

Token CSS globali per testo, superfici, bordo, link, errore e focus. Contrasto
minimo 4,5:1 per testo normale, 3:1 per controlli/focus; target almeno 24×24 CSS px
salvo eccezioni WCAG. Non sopprimere gli adattamenti ai colori forzati.

Il layout deve consentire zoom, reflow a 320 CSS px e text spacing senza perdita di
contenuto. Identificativi e testi lunghi vanno a capo; l'overflow orizzontale
necessario alla tabella resta nel suo contenitore.

Il gate comprende lint template, contrasto sRGB sui token reali, test di focus e
axe con regole WCAG A/AA. Nuovi colori e combinazioni richiedono il controllo
palette. Axe gira in jsdom con color-contrast escluso: non misura rendering,
geometria, scroll, zoom o screen reader; il gate palette non verifica ogni
futuro stile calcolato. Nei test `showModal/close` sono simulate.

## Verifica manuale

- Solo tastiera: Tab/Shift+Tab, Invio, Spazio, Esc; focus visibile, skip link,
  form, errori, pagine, scorrimento tabella e ingresso/uscita dalle conferme.
- VoiceOver/NVDA: landmark, titoli, label/required, errori, conteggi e messaggi
  di recupero, intestazioni/sort e assenza di annunci ripetitivi nel polling.
- Zoom 200%/400%, viewport 320 CSS px, testi lunghi e colori forzati.
- Text spacing: interlinea 1,5; paragrafi 2 em; lettere 0,12 em; parole 0,16 em.
- Annotare browser/tecnologia assistiva e controlli non eseguiti. I test automatici
  non costituiscono una certificazione di conformità.
