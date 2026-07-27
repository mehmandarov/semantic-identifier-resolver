```
.
├── README.md
├── compose.yaml
├── api-endpoints/
│         ├── Dockerfile
│         ├── README.md
│         ├── pom.xml
│         └── src/
├── api-lookup-cache/
│         ├── Dockerfile
│         └── src/
├── http/                 # API walkthrough for the IntelliJ HTTP Client
├── schemas/              # JSON Schemas: the API response contract
├── lookup-worker1/
│         ├── Dockerfile
│         ├── README.md
│         ├── pom.xml
│         └── src/
├── lookup-worker2/
│         ├── Dockerfile
│         ├── pom.xml
│         └── src/
├── lookup-worker3/
│         ├── Dockerfile
│         ├── pom.xml
│         └── src/
└── pom.xml
```

# Development

## URLs

All containers, ports and networks are defined in the [compose.yaml](compose.yaml) file.

| Service Name                    | Container Name  | URL                     | Description                       |
|---------------------------------|-----------------|-------------------------|-----------------------------------|
| API Gateway                     | api-endpoints   | http://localhost:9081/  |                                   | 
| Lookup Worker 1                 | lookup-worker-1 | http://localhost:9085/  | `TAG` → equivalent identifiers (`same-as`) |
| Lookup Worker 2                 | lookup-worker-2 | http://localhost:9086/  | `TAG` → parent asset (`part-of`)  |
| Lookup Worker 3                 | lookup-worker-3 | http://localhost:9087/  | `EPC_DESCRIPTOR` → serial number (`same-as`) |
| RabbitMQ (Management Interface) | some-rabbit     | http://localhost:15672/ | Development only: `guest`/`guest` |
| RabbitMQ (Queue)                | some-rabbit     | http://localhost:5672/  |                                   |
| Jaeger (Tracing UI)             | some-jaeger     | http://localhost:16686/ | Per-lookup trace waterfall        |
| ArangoDB (Cache)                | some-arangodb   | http://localhost:8529/  |                                   |


## Config files

* RabbitMQ: [rabbitmq.conf](rabbitmq.conf)
* ArangoDB: [.env.arangodb](.env.arangodb)

## Local install and run

1. Install [Colima](https://github.com/abiosoft/colima) and `docker` client + `docker compose` plug-in
2. Run `docker compose --env-file .env.arangodb -f compose.yaml -p idekanin-resolver up -d`

Note: the `--env-file` flag is required so that `${TOP_SECRET}` in `compose.yaml` is
interpolated for both the ArangoDB root password and the gateway's cache credentials
(`env_file:` entries alone do not feed Compose variable substitution).

## API endpoints

The gateway implements the asynchronous request-reply pattern: `POST` answers
`202 Accepted` with a `Location` header pointing to a results URL, which the client
polls until the lookup completes.

| Method | Path                       | Codes                                                                                                                    |
|--------|----------------------------|--------------------------------------------------------------------------------------------------------------------------|
| POST   | `/api/lookup`              | `202` accepted; `resultsUrl` in the body and the `Location` header point at the results URL. A repeat POST for a lookup that is pending, in progress or done answers from the cache without queueing new work — unless the `resultsHardRefresh: true` header forces reprocessing; a timed-out or failed lookup is re-queued (retry). `400` blank `id`/`context` |
| GET    | `/api/lookup/results/{requestHash}` | `200` done — every claimed worker responded (with `results`, the aggregated distinct reply elements, and `resultsByWorker`/`failuresByWorker`, one block per worker); `202` pending or in progress (with `claimedBy` and any partial results); `500` all claiming workers failed (with `detail`); `504` timed out (partial results may be present); `404` unknown hash; `400` blank key |
| GET    | `/api/ping`                | `200` liveness check                                                                                                     |

Example:

```shell
curl -i -X POST http://localhost:9081/api/lookup \
  -H 'Content-Type: application/json' \
  -d '{"id": "A-24HA001", "context": "TAG"}'
# HTTP/1.1 202 Accepted
# Location: http://localhost:9081/api/lookup/results/caebb70d-cf1e-5176-afaa-ca094a9d49ac
# {"requestID": "…", "requestHash": "caebb70d-…", "id": "A-24HA001", "context": "TAG",
#  "resultsUrl": "http://localhost:9081/api/lookup/results/caebb70d-cf1e-5176-afaa-ca094a9d49ac"}

curl -i http://localhost:9081/api/lookup/results/caebb70d-cf1e-5176-afaa-ca094a9d49ac
# HTTP/1.1 202 Accepted   (status: pending)
```

Alternatively, [http/api-requests.http](http/api-requests.http) walks the whole API in the
IntelliJ HTTP Client (or VS Code REST Client): liveness checks for the gateway
and all three workers, the full lookup flow with the `requestHash` captured
automatically for the polling requests, an EPC descriptor lookup, a hard
refresh (`resultsHardRefresh` header), an unhandled-context lookup that times
out, and the 400/404 error cases. Hosts are defined per environment in
[http/http-client.env.json](http/http-client.env.json) — pick `dev` when prompted.

The intake records each request as *pending* in the cache before publishing, so the
results URL resolves immediately. The worker's processing step is the plug-in slot:
the resolution logic is use-case specific and is implemented per worker. A completed
lookup answers with two views of the same data: `results`, the aggregated distinct
reply elements across all answering workers, and `resultsByWorker`, one
`{worker, at, reply}` block per worker, so every element's provenance stays
visible. Each reply element carries `{id, context, relationship}`, where
`relationship` states how the returned identifier relates to the input identifier,
per the Paper I interface: `same-as` when both denote the same asset (equal, or an
equivalent identifier in another context), `part-of`/`has-part` when the referents
differ in granularity. Which worker answers what is described under
[Workers](#workers) below.

## Workers

Every worker consumes the same lookup requests from its own queue bound to the
shared exchange, so each worker sees each request. Which **contexts** a worker
answers is deployment configuration — the `worker.contexts` property
(comma-separated, matched case-insensitively) in the worker's
`application.properties`. Requests in other contexts are ignored without a
claim; if no worker covers a context, the request eventually answers `504`
via the gateway's timeout sweep. *How* ids in those contexts are resolved is
the worker's plug-in logic (`queue/QueueHandler`) — the three demo workers use
small hardcoded mappings standing in for real systems:

| Worker            | `worker.contexts` | Answers with                                    | Relationship |
|-------------------|-------------------|--------------------------------------------------|--------------|
| `lookup-worker-1` | `TAG`             | Serial number and EPC contractor's descriptor    | `same-as`    |
| `lookup-worker-2` | `TAG`             | The system the tagged asset belongs to           | `part-of`    |
| `lookup-worker-3` | `EPC_DESCRIPTOR`  | Serial number for the contractor's descriptor    | `same-as`    |

Demo data: tag `A-24HA001` ≡ serial `SN-1042-77` ≡ EPC descriptor `EJ101A`,
part of `SYSTEM-24` (and a second set: `A-24HA002` / `SN-1042-78` / `EJ101B`).
A `TAG` lookup is answered by workers 1 *and* 2 — two `claimedBy` entries and
two blocks in `resultsByWorker` — while an `EPC_DESCRIPTOR` lookup is answered
by worker 3 alone. A known context with an unknown id answers `done` with an
empty reply: the source was consulted and had nothing.

Worker 2 additionally exposes a **FAKE, dev/demo-only** slowdown knob,
`worker.fake-processing-delay-ms` (default `0` = off), which sleeps inside
the processing step after the claim. Set it to `15000` for a visibly slow
but successful lookup, or to `90000` (beyond the 60s lookup timeout) to
watch the whole completion story in Jaeger and the API: worker 1 answers a
`TAG` lookup immediately, but because worker 2 also claimed it, the lookup
stays `202 in_progress` (worker 1's partial results already visible), the
sweep answers `504 timed-out` at 60s, and worker 2's late reply finally
completes the sign-up sheet and flips it to `200 done` (accept-late). The
same lifecycle is covered end-to-end by the `SlowSimulatedWorker` mock in
the e2e suite. Never set this in production.

Turning it on and off (no image rebuild needed — the property maps to the
`WORKER_FAKE_PROCESSING_DELAY_MS` environment variable, which
`compose.yaml` passes through):

```shell
# ON: recreate worker 2 with a 90s delay (exceeds the 60s timeout)
WORKER2_FAKE_DELAY_MS=90000 docker compose --env-file .env.arangodb \
  -f compose.yaml -p idekanin-resolver up -d lookupworker2

# OFF: recreate without the variable (falls back to 0)
docker compose --env-file .env.arangodb \
  -f compose.yaml -p idekanin-resolver up -d lookupworker2
```

Worker 2 logs a `WARN` at start-up and per request while the delay is
active, so an accidentally slowed deployment is loud.

Each worker's `GET /api/ping` introduces it, answering `pong` with the
worker's name and its configured contexts:

```json
{"message": "pong", "worker": "lookup-worker-1", "contexts": ["TAG"]}
```

## Lookup lifecycle, worker tracking and timeout

The workers never touch the cache or its database. Their only output is
**status events** published to a status queue (exchange `exchange_id_mapper`,
routing key `routing_id_lookup_status`, queue `queue_id_lookup_status`); the
gateway's status consumer is the single writer of worker output to the cache.
This replaces the earlier unused fire-and-forget "ACK" reply.

Flow of one request:

1. `POST /api/lookup` — the gateway records the request as `pending` in the
   cache, publishes it to the work queue and answers `202` + `Location`.
   The intake is cache-aware: a repeat POST for a lookup that is already
   pending, in progress or done answers from the cache without queueing new
   work, while a timed-out or failed lookup is reset to `pending` (fresh
   `requestID` and `createdAt`, stale `detail` cleared) and re-published as
   a retry. Sending the `resultsHardRefresh: true` header forces this reset
   for any cached lookup — old replies and provenance are dropped and the
   lookup runs through the workers again (the claim history is kept).
2. Every worker sees the request (each worker consumes from its own queue
   bound to the shared exchange). A worker whose `worker.contexts` does not
   cover the request's context ignores it without a claim; each worker that
   does cover it publishes a `claimed` event
   (`{type, requestHash, requestID, id, context, worker, at}`), then runs its
   processing step.
3. The gateway applies the claim: status `pending` → `in_progress`, and the
   `{worker, at, requestID}` entry is appended to the document's `claimedBy`
   list — the request's sign-up sheet: a poll shows exactly which worker(s)
   picked the request up, and completion is judged against it.
4. The worker publishes `done` (with `reply`, a list of
   `{id, context, relationship}` elements) or `failed` (with `detail`).
5. The gateway applies the outcome and recomputes the lifecycle: replies are
   merged into `resultsByWorker` and failures into `failuresByWorker`, both
   keyed by worker name (a redelivery replaces the worker's previous block).
   The lookup becomes `done` only when **every worker that claimed the
   current occurrence** (matching `requestID`) has responded and at least
   one of them delivered results; it becomes `error` when all of them
   failed. Until then it stays `in_progress` — partial results are already
   visible on polls — and a fresh claim reopens even a `done` lookup.
   `resolvedBy`/`resolvedAt` track the latest reply.
6. A scheduled sweep in the gateway marks lookups still `pending`/`in_progress`
   after `lookup.timeout-seconds` (measured from `createdAt`) as `timed-out` —
   one mechanism covers both "never picked up" and "a claimant never
   finished". Partial results collected before the timeout stay visible on
   the `504` response. A late reply that completes the sign-up sheet still
   resolves the lookup (accept-late policy).
7. Polls of `GET /api/lookup/results/{requestHash}` reflect the state: `202` while
   `pending`/`in_progress` (body carries `claimedBy` and any partial
   results), `200` when `done`, `500` on `error`, `504` when `timed-out`.

Timeout configuration (gateway `application.properties`):

| Property                    | Default | Meaning                                                                 |
|-----------------------------|---------|-------------------------------------------------------------------------|
| `lookup.timeout-seconds`    | `60`    | Window after `createdAt` before a non-finished lookup is timed out. `0` or negative disables the sweep. |
| `lookup.timeout-sweep-every`| `10s`   | Cadence of the sweep (any Quarkus duration; `off` disables the trigger). |

## API contract (JSON Schema)

The response bodies of the public API are specified as JSON Schema files in
[schemas/](schemas/), shared with clients as the contract:

- [lookup-receipt.schema.json](schemas/lookup-receipt.schema.json) — the `202`
  receipt of `POST /api/lookup`.
- [lookup-state.schema.json](schemas/lookup-state.schema.json) — every state
  answered by `GET /api/lookup/results/{requestHash}` (`pending`, `in_progress`,
  `done`, `error`, `timed-out`), including which fields each status carries.

The end-to-end suite validates live responses of every state against these
schemas (rest-assured's `json-schema-validator`), so a contract change that
is not reflected in the schema files fails CI.

## Tracing

Every service ships [OpenTelemetry](https://quarkus.io/guides/opentelemetry)
(`quarkus-opentelemetry`) and exports traces over OTLP to the Jaeger
container — open http://localhost:16686/ and select a service to see the
per-lookup waterfall. Trace context propagates through the RabbitMQ message
headers, so one `POST /api/lookup` shows as a single trace spanning the
gateway intake, each worker's consume/publish, the gateway's status
consumer, and the cache writes (`ArangoService` methods are annotated with
`@WithSpan`). A `TAG` lookup, for example, renders the fan-out to workers 1
and 2 and the fan-in of their replies on one timeline.

Configuration lives in each service's `application.properties`
(`quarkus.otel.service.name`, `quarkus.otel.exporter.otlp.traces.endpoint`).
Tracing is disabled under the test profile, where no collector runs.

Note: the `quarkus-microprofile` umbrella extension ships MP Telemetry
defaults that disable the OTel SDK (`quarkus.otel.sdk.disabled=true`) and
point the OTLP exporter at `localhost:4317`. The properties files therefore
re-enable the SDK explicitly and set the Jaeger endpoint in **both** property
families (`quarkus.otel.*` and MP-style `otel.*`) — removing either half
silently turns tracing off again.


## Manual start of services
### Start RabbitMQ
docker run -d --hostname my-rabbit --name some-rabbit -p 15672:15672 -p5672:5672 rabbitmq:3-management

### Start ArangoDB
docker run -d --hostname my-arangodb --name some-arangodb -p 8529:8529 -e ARANGO_ROOT_PASSWORD=openSesame arangodb:3.11.6

## Cache TTL

Cache documents in ArangoDB are expired automatically by an ArangoDB
[**TTL index**](https://docs.arangodb.com/3.11/index-and-search/indexing/working-with-indexes/ttl-indexes/)
maintained on the `createdAt` attribute of every cache document.

### Configuration

| Property            | Default | Meaning                                                              |
|---------------------|---------|----------------------------------------------------------------------|
| `cache.ttl-seconds` | `3600`  | Retention window in seconds after `createdAt`. `0` or negative disables TTL. |

The property lives in the gateway's `application.properties` (the only
service that talks to ArangoDB). The value is read at start-up by
`ArangoService`; changing it and restarting the gateway re-applies the index
with the new `expireAfter` window.

### Schema and start-up bootstrap

ArangoDB is schemaless, so there is no separate DDL file to maintain — the
"schema" is defined in code (`api-endpoints/.../cache/ArangoService`) and
applied every time the gateway starts. Concretely, `ArangoService` runs the
following idempotent bootstrap in its constructor:

1. Ensure the `idekanin` database exists (create if missing).
2. Ensure the `id_request_cache` collection exists (create if missing).
3. Ensure a TTL index on `createdAt` with `expireAfter = cache.ttl-seconds`,
   using the driver's `ensureTtlIndex` (creates if missing, no-op otherwise).
   Skipped entirely when `cache.ttl-seconds <= 0`.

Effective document layout, per request hash (all fields are written by the
gateway — worker-originated fields arrive as status events):

| Field         | Type            | Source                                              | Purpose                                    |
|---------------|-----------------|-----------------------------------------------------|--------------------------------------------|
| `_key`        | string (UUID v5)| gateway on POST                                     | Deterministic hash of `id + "_" + context` |
| `requestID`   | string (UUID v4)| gateway on POST (or event on recreate)              | Correlation ID of the request occurrence that (re)queued the work. Every POST receipt carries its own fresh `requestID`; a repeat POST answered from the cache does not replace the stored one |
| `id`          | string          | gateway on POST                                     | Business ID being looked up                |
| `context`     | string          | gateway on POST                                     | Business context                           |
| `status`      | `pending` / `in_progress` / `done` / `error` / `timed-out` | gateway       | Current lifecycle state                    |
| `createdAt`   | number (epoch s)| gateway on POST (or event on recreate)              | **TTL anchor** and timeout anchor — refreshed only when a timed-out/failed lookup is retried by a new POST |
| `claimedBy`   | list of `{worker, at, requestID}` | `claimed` events                  | Sign-up sheet and pick-up history, kept across retries; completion is judged against the claims of the current `requestID` |
| `resultsByWorker` | list of `{worker, at, reply, requestID}` | `done` events             | One block per answering worker; `reply` is that worker's resolved elements `{id, context, relationship}`. `GET` also answers `results`: the distinct reply elements aggregated across workers, computed on read |
| `failuresByWorker` | list of `{worker, at, detail, requestID}` | `failed` events          | One block per failed worker — a failure counts as a response, so a broken worker cannot keep the lookup open |
| `resolvedBy`  | string          | `done`/`failed` events                              | Provenance: which worker answered last     |
| `resolvedAt`  | string (ISO instant) | `done`/`failed` events                         | Provenance: when it answered               |
| `detail`      | string          | `failed` events or the timeout sweep                | Human-readable failure reason              |

### How documents are deleted

Deletion is done entirely by **ArangoDB itself**, not by the application:

1. At start-up, `ArangoService` calls `ensureTtlIndex` on the
   `id_request_cache` collection, indexing `createdAt` with
   `expireAfter = cache.ttl-seconds`.
2. ArangoDB runs a background thread (default cadence: every 30 seconds)
   that scans the TTL index and removes any document whose
   `createdAt + expireAfter <= now`.
3. Deletion is best-effort and eventually consistent: an expired document
   may briefly still be returned by a `GET /api/lookup/results/{hash}` until the
   next TTL sweep. Once removed, the endpoint answers `404`, and a fresh
   `POST` for the same `(id, context)` starts a new cache entry.

If `cache.ttl-seconds <= 0`, no TTL index is created and documents live
until deleted by hand.

### Migration note for existing deployments

Restarting the gateway against an ArangoDB instance that already contains
`id_request_cache` documents from before this change is safe:

- `ensureTtlIndex` will add the TTL index to the existing collection on the
  first boot after upgrade.
- Documents written before the upgrade do **not** carry `createdAt`;
  ArangoDB's TTL sweeper simply ignores documents missing the indexed field,
  so those legacy documents persist indefinitely. They can be removed by
  hand, or backfilled by setting `createdAt` on them (e.g. via an AQL
  `UPDATE` against the collection).
- Every new write from this version onwards carries `createdAt` and is
  therefore subject to expiry.

## End-to-end tests

The `api-endpoints` module ships a full end-to-end test suite
(`e2e.EndToEndLookupIT`) that exercises the whole pipeline in a single JVM:
`POST /api/lookup` → RabbitMQ → in-test worker consumer → status events →
gateway status consumer → ArangoDB → `GET /api/lookup/results/{hash}` → cached repeat
lookup, plus the in-progress, timed-out, accept-late and multi-worker
fan-in paths.

The suite uses [Testcontainers](https://java.testcontainers.org/) to spin up
real `rabbitmq:3-management` and `arangodb:3.11.6` containers, so a working
Docker daemon is required. Tests are wired to the `failsafe` phase and are
**skipped by default** (`-DskipITs=true`).

Run them with:

```shell
mvn -pl api-endpoints verify -DskipITs=false
```

### Colima users

Testcontainers looks for the Docker socket at `/var/run/docker.sock` by
default, which does not exist on Colima setups. Point it at Colima's socket
before running the tests:

```shell
export DOCKER_HOST="unix://${HOME}/.colima/default/docker.sock"
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn -pl api-endpoints verify -DskipITs=false
```

## Continuous integration

Every push and pull request runs the full pipeline via GitHub Actions
(`.github/workflows/ci.yml`):

- Sets up Temurin JDK 25 with Maven cache.
- Runs `mvn -B -ntp verify -DskipITs=false` — unit tests, Quarkus build and
  the Testcontainers-backed end-to-end suite. GitHub-hosted `ubuntu-latest`
  runners ship a Docker daemon, so no extra setup is needed for
  Testcontainers.
- Uploads the Surefire / Failsafe test reports as workflow artefacts if the
  run fails. Coverage is measured locally via the quarkus-jacoco report in
  `api-endpoints/target/jacoco-report/`.

Maven dependencies and GitHub Actions themselves are kept up to date by
Dependabot (`.github/dependabot.yml`, weekly cadence, PRs grouped by Quarkus
platform vs. testing libraries).