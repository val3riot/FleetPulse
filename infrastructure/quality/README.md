# Quality gate backend

Dalla radice del repository, con JDK 21, Python 3.9+ e Docker attivo per i test
Testcontainers. Maven è fornito dal wrapper (3.9.16); il gate accetta Maven
3.9.13–3.x, coerente con le immagini di build Docker del progetto.

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
python3 -m unittest discover -s infrastructure/load -p 'test_*.py'
python3 -m unittest discover -s infrastructure/operations -p 'test_*.py'
python3 -m unittest discover -s infrastructure/e2e -p 'test_*.py'
```

Tutti i comandi devono terminare con exit 0. Maven esegue unit/integration test,
compila anche i test e controlla lo stile di tutti i moduli del reactor. Le prove
E2E Docker complete hanno comandi separati in [infrastructure/e2e](../e2e/README.md):
non vengono avviate automaticamente dalla discovery Python.

`clean` evita risultati incrementali obsoleti. Non usare skipTests, esclusioni di
moduli o skip dei plugin come evidenza del gate completo. Se l'IDE compila negli
stessi target mentre Maven lavora, usare un checkout/copia temporanea senza target
e senza compilazione IDE concorrente. Non occorre arrestare processi IDE o lo
stack di lavoro; Testcontainers gestisce risorse dedicate.

Applicare anche i [controlli di coerenza](../consistency/README.md)
e la prova E2E Kafka warm/cold.

## Formatting

Spotless Maven 3.10.4 usa Eclipse JDT 4.34 con profilo versionato
[eclipse-formatter.xml](eclipse-formatter.xml): spazi, indentazione 4, larghezza
obiettivo 100, newline LF e finale, nessuna riorganizzazione dei membri.
Lo stile è coerente con `.editorconfig`; le proprietà `ij_java_*` restano consigli
per IntelliJ, il check Maven è l'autorità per il formatter. Il wrapping a 100 non
è una regola lessicale che vieta stringhe/commenti indivisibili più lunghi.

```bash
./mvnw --batch-mode --no-transfer-progress spotless:apply
./mvnw --batch-mode --no-transfer-progress spotless:check
```

`apply` modifica i sorgenti main/test; `check` è read-only e fa fallire `verify`
se rileva differenze. File generati sotto target e documenti tmp sono esclusi.
Eseguire dalla radice per risolvere il profilo comune anche con `-pl`.

## Dipendenze e compiler

Maven Enforcer 3.6.3, fase validate:

- JDK 21 e Maven compatibile;
- nessuna dichiarazione duplicata della stessa dipendenza nel POM;
- convergenza delle versioni nel grafo Maven risolto di ciascun modulo.

La dependencyManagement Spring Boot resta la fonte delle versioni gestite;
le eventuali proprietà di override sono motivate nel POM e verificate dalla
convergence rule, senza esclusioni globali. Le regole analizzano
le dipendenze del progetto, non la supply chain interna di ogni plugin.
Non è una scansione CVE, una verifica licenze o una certificazione di sicurezza.

Il compiler usa `-Xlint:all,-processing`, showWarnings e failOnWarning per main e
test. `processing` è escluso perché annotazioni Spring/JPA/JUnit sono elaborate
anche a runtime e non devono essere tutte claimed da un annotation processor;
MapStruct rimane abilitato e i suoi errori/warning non vengono ignorati.

Eccezioni al lint sono locali e motivate: `@SuppressWarnings("try")` sulle due
fixture TCP/pipeline che mantengono socket volutamente non lette e chiudono
esplicitamente risorse per testare shutdown/capacità. Nessuna soppressione generale
di unchecked, deprecation o serial: i casi rilevati sono corretti nei sorgenti.

I quattro moduli che usano Mockito caricano l'agent esplicitamente in Surefire,
risolto dalla versione BOM, senza dynamic self-attach. `-Xshare:off` riguarda solo
le JVM dei test ed evita il warning CDS dell'agent; non cambia i processi runtime
Docker. Le librerie senza Mockito non richiedono un agent o download aggiuntivo.

## Limiti e diagnosi

Un test fallito, un warning compiler, un conflitto di dipendenze o una differenza
di formatting bloccano il gate. I warning di runtime/test infrastructure non sono
warning javac: i test di guasto possono produrre log WARN/ERROR attesi. Valutare
l'esito Surefire e le asserzioni, non imporre l'assenza di ogni stringa ERROR nei log.
Le immagini Testcontainers restano configurate nei test e richiedono Docker/rete.
Il gate locale è riproducibile tramite wrapper, configurazioni e versioni
fissate nel repository.

## Rapporti di validazione

Conservare risultati datati, log e checklist in `tmp`, separati dalle linee tecniche.
Un report identifica commit o snapshot verificato, toolchain, immagini, fixture,
scenari ed evidenze originali con checksum. Se riusa risultati precedenti, deve
mostrare la continuità dei sorgenti e indicare differenze di ambiente o dipendenze;
un dato storico non diventa una nuova esecuzione sul commit corrente.

Separare unit test, prove di integrazione, carico, controlli statici e accettazione
manuale. Riportare il denominatore dei percentili, warm-up, durata, risorse e
intervallo di campionamento; ACK e timestamp di elaborazione non sostituiscono
l’evidenza del commit SQL. Indicare errori e tentativi falliti conservati.

L’esito funzionale, il gate CVE e la presenza della CI sono risultati distinti.
Non sommare prove sovrapposte come test nuovi, non presentare smoke come benchmark
e non dedurre accessibilità completa da axe/jsdom. Una pubblicazione richiede
riesame dei rischi aperti e dei limiti della configurazione locale.

Il [workflow CI](../ci/README.md) esegue questi gate su runner GitHub separati,
con test di integrazione e artefatti diagnostici anche dopo un fallimento.
