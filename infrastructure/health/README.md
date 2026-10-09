# FP-048 health acceptance verifier

From the repository root, with Docker available and the project `.env` configured:

```bash
python3 infrastructure/health/verify.py --output tmp/fp048-health-new
```

Python uses only the standard library and shares isolated-stack setup with the
FP-047 harness. The output directory must be new. The verifier builds the backend,
uses a unique `fp048-*` Compose project with dynamic loopback ports and owned
volumes, and removes those resources on exit, including failure/SIGINT. It does
not stop the user's stack. A private resolved Compose configuration is removed
by cleanup; it must not be published.

Checks cover real HTTP root/liveness/readiness responses, Redis outage and API
fallback, processor projection failure, API/processor cold application startup
while Redis remains stopped, PostgreSQL outage with cache hit/miss, Kafka outage,
and dependency recovery with successful persistence and unchanged application
start times. The intentional application restart used for the Redis startup
check is separate from the subsequent recovery checks, which require no restart.
Dynamic application ports are rediscovered after that intentional restart.

`checks.json` stores observed status/body/duration; `summary.json` is written only
on success, and `failure.json` on failure. Java tests additionally cover TCP and
consumer lifecycle, application availability states, metadata validation and
bounded Kafka timeouts. The project contracts are in
[Observability](../../docs/11_OBSERVABILITY.md#health-liveness-e-readiness--fp-048)
and [the test strategy](../../docs/12_STRATEGIA_DI_TEST.md#health-e-readiness--fp-048).

A shortened FP-047 smoke can verify readiness-based startup without repeating
the full reference load:

```bash
python3 infrastructure/load/run.py --duration 10 --vehicles 5 --scenario baseline --output tmp/fp048-load-smoke
```
