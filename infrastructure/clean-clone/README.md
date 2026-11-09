# Verifica da clone pulito

Prerequisiti: Git, Python 3.9+, JDK 21, Node indicato in
[.nvmrc](../../frontend/fleet-dashboard/.nvmrc), npm e Docker con Compose.
Sono necessari accesso al repository e ai registry, spazio disco e memoria per
uno stack completo aggiuntivo. Le cache di download possono essere riutilizzate.

Dalla radice del repository:

```sh
python3 infrastructure/clean-clone/verify.py --output tmp/clean-clone-check
```

La directory di output deve essere nuova. Per scegliere una branch o un tag,
usare `--ref`; per un altro remoto, `--repository`.

Lo script clona il remoto, esegue Maven `clean verify`, i test infrastrutturali,
`npm ci` e il gate frontend. Crea `.env` esclusivamente da `.env.example`,
costruisce e avvia tutti i servizi con un progetto Compose dedicato, porte
loopback dinamiche e volumi nuovi. Verifica migrazioni, registrazione veicolo,
telemetria TCP, persistenza, replay, alert, Redis, proxy e routing frontend,
Prometheus e Grafana. Il controllo HTTP delle pagine non sostituisce i test
visuali o con screen reader.

Al termine rimuove container, reti, volumi e immagini locali del progetto di
prova, oltre alla configurazione temporanea. Il clone e i log restano nella
directory di output. `summary.json` viene scritto solo in caso di successo;
`checks.json` contiene le evidenze e `failure.json` descrive eventuali errori.
Non vengono copiati `.env`, dipendenze o artefatti del workspace.

Test dello script:

```sh
python3 -m unittest discover -s infrastructure/clean-clone -p 'test_*.py'
```
