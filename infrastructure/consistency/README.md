# Controlli di coerenza backend — FP-055

Eseguire dalla radice del repository con JDK 21, Docker attivo e `.env`
configurato. Le verifiche runtime costruiscono uno stack isolato e lo rimuovono
con container, reti e volumi propri; non modificano lo stack di lavoro.

```bash
python3 -m venv tmp/consistency-tools
tmp/consistency-tools/bin/python -m pip install -r infrastructure/consistency/requirements.txt
tmp/consistency-tools/bin/python infrastructure/consistency/verify_links.py
python3 infrastructure/consistency/verify_config.py
python3 infrastructure/consistency/export_runtime.py --output tmp/backend-contracts
tmp/consistency-tools/bin/python infrastructure/consistency/verify_contracts.py --output tmp/backend-contracts
java -jar /percorso/plantuml.jar --check-syntax 'docs/diagrammi/*.puml'
```

Usare una nuova directory di output. `export_runtime.py` esporta OpenAPI,
colonne, constraint, indici e migrazioni dal database effettivo. Il controllo
contratti valida gli 11 esempi JSON documentati, le 11 operazioni REST, codici
errore e HTTP status, default di paginazione, colonne SQL, constraint/indici
esplicitamente documentati e le cinque migrazioni. Il controllo configurazione
confronta `.env.example`, Compose e deployment; quello dei link controlla file
e anchor Markdown locali, senza richieste HTTP ai link esterni.

Questi controlli affiancano il [quality gate](../quality/README.md) e gli
[E2E](../e2e/README.md), inclusa la prova Kafka warm/cold di FP-056. Un fallimento
impedisce la chiusura. Gli script usano asserzioni: non eseguirli con `python -O`.
