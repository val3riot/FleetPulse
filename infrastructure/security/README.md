# Verifica security baseline locale — FP-054

Dalla radice del progetto, con Docker attivo, Python 3.9+ e `.env` configurato:

```bash
python3 infrastructure/security/verify.py --output tmp/fp054-security
python3 infrastructure/e2e/verify_nominal.py --output tmp/fp054-nominal
```

Usare directory di output nuove. La verifica risolve la configurazione Compose
senza stamparne i segreti, costruisce le immagini e avvia un progetto isolato con
porte loopback dinamiche e volumi propri. Comprende i tre backend, il simulatore
attivo, PostgreSQL, Redis, Kafka, Flyway, kafka-init, Prometheus e Grafana.

Controlla i bind originali e quelli runtime, UID effettivi e PID 1 dei servizi,
identità non-root e successo dei job, esclusione `.env` da Git, allowlist Actuator,
assenza di dettagli health, tre target Prometheus UP, accesso Grafana anonimo
negato alla gestione datasource e telemetria del simulatore realmente persistita.
Il secondo comando verifica separatamente l'intero flusso nominale REST → TCP →
Kafka → PostgreSQL → Redis → API con conteggi esatti.

Ogni comando rimuove esclusivamente container, reti e volumi del proprio progetto
anche in caso di errore; `cleanup.json` ne verifica l'assenza. La configurazione
risolta temporanea ha permessi `0600` e viene rimossa alla chiusura. Gli output
persistenti contengono risultati e identità tecniche, senza credenziali.
Non viene modificato lo stack di lavoro. Un'interruzione non gestibile, come
SIGKILL, può richiedere pulizia manuale del solo progetto riportato nell'output.

La baseline e i limiti di sicurezza sono definiti in
[Deployment](../../docs/13_DEPLOYMENT.md#security-baseline-locale--fp-054).
Questa verifica non è un penetration test né una scansione CVE.
