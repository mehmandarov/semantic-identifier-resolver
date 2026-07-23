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
| POST   | `/api/lookup/mock`         | Same as above, without publishing to the queue                                                                           |
| GET    | `/api/cache/{requestHash}` | `200` done (with `results`); `202` pending; `500` error (with `detail`); `404` unknown hash; `400` blank key             |
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
the resolution logic is use-case specific and is implemented per worker. Version 1
workers log the request and acknowledge; none writes results back yet, so lookups
still report `pending`; the fan-in write is the remaining placeholder.


## Manual start of services
### Start RabbitMQ
docker run -d --hostname my-rabbit --name some-rabbit -p 15672:15672 -p5672:5672 rabbitmq:3-management

### Start ArangoDB
docker run -d --hostname my-arangodb --name some-arangodb -p 8529:8529 -e ARANGO_ROOT_PASSWORD=openSesame arangodb:3.11.6