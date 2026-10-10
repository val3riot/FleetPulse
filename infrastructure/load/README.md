# FP-047 load acceptance harness

Python 3 standard library, Docker Compose and the project's `.env` are required.
Run from any directory; the harness resolves the repository root itself.

```bash
python3 -m unittest discover -s infrastructure/load -p 'test_*.py'
python3 infrastructure/load/run.py --duration 10 --vehicles 5 --output tmp/fp047-smoke
python3 infrastructure/load/run.py --output tmp/fp047-reference
```

The default runs a warm-up, then the full 50-vehicle baseline and mixed scenario
(300 seconds each, one unique frame every 2 seconds). A shortened run is marked
`fullReference: false` and cannot close FP-047. Output directories must be new.
The command exits unsuccessfully if acceptance criteria fail.

The harness derives an isolated Compose stack from the project's configuration,
builds the three backend applications, uses dynamically assigned loopback ports
and project-owned volumes, and removes that stack on exit. Existing containers
and data are untouched. A private resolved configuration temporarily contains
credentials, has mode 0600, and is deleted during cleanup. Do not publish that
file. SIGINT runs cleanup; SIGKILL/host failure require manual cleanup of the
unique `fp047-*` project identified in the startup message.

Each scenario provisions separate vehicles, opens one TCP connection per vehicle,
waits for the backend readiness probes, reads framed and correlated ACKs,
schedules with a monotonic clock and records
late slots. The mixed scenario closes a connection halfway through a selected
payload (1% probability), reconnects and resends the full message; independently
it resends selected accepted messages unchanged (2%). Seed defaults to 47.
Unexpected failures are retained rather than silently retried.
FP-056 separates connect timeout (1s) from framed ACK read timeout
(`--ack-timeout`, default 7s), allowing the 5s gateway decision budget plus
transport margin. Custom gateway budgets require a larger ACK timeout.
A longer socket timeout does not relax the latency or late-slot acceptance criteria.

JSON artifacts contain frame IDs/results, raw resource samples, exact post-commit
latencies, nearest-rank p50/p95/p99 and acceptance errors. Reconciliation covers
SQL IDs, alerts, Kafka raw/rejected/dead-letter counts and final consumer lag.
Resource sampling waits five seconds between collections; actual spacing also
includes collection time, and consumer lag is sampled every six collections.
Resources include Docker working set/CPU and application JVM/GC/pool/connection
metrics; the contract and operational budgets are in
[the project test strategy](../../docs/12_STRATEGIA_DI_TEST.md#contratto-di-accettazione-fp-047).
The latency requires synchronized gateway/processor clocks. Historical processor
Grafana p95 remains the local handler latency, a different measurement.
