
### Start RabbitMQ
podman run -d --hostname my-rabbit --name some-rabbit -p 15672:15672 -p5672:5672 rabbitmq:3-management

### Start ArangoDB
podman run -d --hostname my-arangodb --name some-arangodb -p 8529:8529 -e ARANGO_ROOT_PASSWORD=openSesame arangodb:3.11.6