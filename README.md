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
2. Run `docker compose -f compose.yaml -p idekanin-resolver up -d`


## Manual start of services
### Start RabbitMQ
docker run -d --hostname my-rabbit --name some-rabbit -p 15672:15672 -p5672:5672 rabbitmq:3-management

### Start ArangoDB
docker run -d --hostname my-arangodb --name some-arangodb -p 8529:8529 -e ARANGO_ROOT_PASSWORD=openSesame arangodb:3.11.6