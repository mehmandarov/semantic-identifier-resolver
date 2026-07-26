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
├── lookup-worker1/
│         ├── Dockerfile
│         ├── README.md
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
| Lookup Worker 1                 | lookup-worker-1 | http://localhost:9085/  |                                   |
| RabbitMQ (Management Interface) | some-rabbit     | http://localhost:15672/ | Development only: `guest`/`guest` |
| RabbitMQ (Queue)                | some-rabbit     | http://localhost:5672/  |                                   |
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
| POST   | `/api/lookup`              | `202` accepted + `Location`; `400` blank `id`/`context`                                                                  |
| GET    | `/api/cache/{requestHash}` | `200` done (with `results`); `202` pending or in progress (with `claimedBy`); `500` error (with `detail`); `504` timed out; `404` unknown hash; `400` blank key |
| GET    | `/api/lookup/ping`         | `200` liveness check                                                                                                     |

Example:

```shell
curl -i -X POST http://localhost:9081/api/lookup \
  -H 'Content-Type: application/json' \
  -d '{"id": "A-24HA001", "context": "TAG"}'
# HTTP/1.1 202 Accepted
# Location: http://localhost:9081/api/cache/caebb70d-cf1e-5176-afaa-ca094a9d49ac

curl -i http://localhost:9081/api/cache/caebb70d-cf1e-5176-afaa-ca094a9d49ac
# HTTP/1.1 202 Accepted   (status: pending)
```

The intake records each request as *pending* in the cache before publishing, so the
results URL resolves immediately. The worker's processing step is the plug-in slot:
the resolution logic is use-case specific and is implemented per worker. The version 1
worker implements the simplest case, echoing the input tuple as its result.

## Lookup lifecycle, worker tracking and timeout

The workers never touch the cache or its database. Their only output is
**status events** published to a status queue (exchange `exchange_id_mapper`,
routing key `routing_id_lookup_status`, queue `queue_id_lookup_status`); the
gateway's status consumer is the single writer of worker output to the cache.
This replaces the earlier unused fire-and-forget "ACK" reply.

Flow of one request:

1. `POST /api/lookup` — the gateway records the request as `pending` in the
   cache, publishes it to the work queue and answers `202` + `Location`.
2. A worker consumes the request and immediately publishes a `claimed` event
   (`{type, requestHash, requestID, id, context, worker, at}`), then runs its
   processing step.
3. The gateway applies the claim: status `pending` → `in_progress`, and the
   `{worker, at}` pair is appended to the document's `claimedBy` list — so a
   poll shows exactly which worker(s) picked the request up.
4. The worker publishes `done` (with `results`) or `failed` (with `detail`).
5. The gateway applies the outcome: `done` (results + `resolvedBy`/`resolvedAt`)
   or `error`. A failure never overwrites results another worker already
   delivered; claims never regress a finished lookup.
6. A scheduled sweep in the gateway marks lookups still `pending`/`in_progress`
   after `lookup.timeout-seconds` (measured from `createdAt`) as `timed-out` —
   one mechanism covers both "never picked up" and "worker died mid-job".
   A result arriving *after* the timeout still resolves the lookup
   (accept-late policy).
7. Polls of `GET /api/cache/{requestHash}` reflect the state: `202` while
   `pending`/`in_progress` (body carries `claimedBy`), `200` when `done`,
   `500` on `error`, `504` when `timed-out`.

Timeout configuration (gateway `application.properties`):

| Property                    | Default | Meaning                                                                 |
|-----------------------------|---------|-------------------------------------------------------------------------|
| `lookup.timeout-seconds`    | `60`    | Window after `createdAt` before a non-finished lookup is timed out. `0` or negative disables the sweep. |
| `lookup.timeout-sweep-every`| `10s`   | Cadence of the sweep (any Quarkus duration; `off` disables the trigger). |


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
| `requestID`   | string (UUID v4)| gateway on POST (or event on recreate)              | Correlation ID for one request occurrence  |
| `id`          | string          | gateway on POST                                     | Business ID being looked up                |
| `context`     | string          | gateway on POST                                     | Business context                           |
| `status`      | `pending` / `in_progress` / `done` / `error` / `timed-out` | gateway       | Current lifecycle state                    |
| `createdAt`   | number (epoch s)| gateway on POST (or event on recreate)              | **TTL anchor** and timeout anchor — never overwritten by updates |
| `claimedBy`   | list of `{worker, at}` | `claimed` events                             | Pick-up history: which worker(s) claimed the request |
| `results`     | list of `{id, context}` | `done` events                               | Resolved results returned by `GET`         |
| `resolvedBy`  | string          | `done`/`failed` events                              | Provenance: which worker answered          |
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
   may briefly still be returned by a `GET /api/cache/{hash}` until the
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
gateway status consumer → ArangoDB → `GET /api/cache/{hash}` → cached repeat
lookup, plus the in-progress, timed-out and accept-late paths.

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
- Uploads the JaCoCo coverage reports as workflow artefacts, and Surefire /
  Failsafe test reports if the run fails.

Maven dependencies and GitHub Actions themselves are kept up to date by
Dependabot (`.github/dependabot.yml`, weekly cadence, PRs grouped by Quarkus
platform vs. testing libraries).