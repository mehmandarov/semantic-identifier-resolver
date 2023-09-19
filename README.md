
### Start RabbitMQ
podman run -d --hostname my-rabbit --name some-rabbit -p 15672:15672 -p5672:5672 rabbitmq:3-management

### Start Redis
podman run -d --hostname my-redis --name some-redis -p 6379:6379 redis:7