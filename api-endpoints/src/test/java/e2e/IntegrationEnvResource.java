package e2e;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots RabbitMQ + ArangoDB testcontainers and wires their coordinates into
 * Quarkus configuration so both the gateway and the in-test worker consumer
 * talk to the same infrastructure.
 */
public class IntegrationEnvResource implements QuarkusTestResourceLifecycleManager {

    static final String ARANGO_ROOT_PASSWORD = "openSesame";

    private RabbitMQContainer rabbit;
    private GenericContainer<?> arango;

    @Override
    public Map<String, String> start() {
        rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"));
        rabbit.start();

        arango = new GenericContainer<>(DockerImageName.parse("arangodb:3.11.6"))
                .withExposedPorts(8529)
                .withEnv("ARANGO_ROOT_PASSWORD", ARANGO_ROOT_PASSWORD)
                .waitingFor(Wait.forHttp("/_api/version")
                        .withBasicCredentials("root", ARANGO_ROOT_PASSWORD)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        arango.start();

        Map<String, String> props = new HashMap<>();
        // RabbitMQ
        props.put("rabbitmq-host", rabbit.getHost());
        props.put("rabbitmq-port", String.valueOf(rabbit.getAmqpPort()));
        props.put("rabbitmq-username", rabbit.getAdminUsername());
        props.put("rabbitmq-password", rabbit.getAdminPassword());

        // Outgoing channel (gateway -> RabbitMQ)
        props.put("mp.messaging.outgoing.idMapper.connector", "smallrye-rabbitmq");
        props.put("mp.messaging.outgoing.idMapper.exchange.name", "exchange_id_mapper");
        props.put("mp.messaging.outgoing.idMapper.exchange.type", "direct");
        props.put("mp.messaging.outgoing.idMapper.default-routing-key", "routing_id_lookup_with_ctx");

        // Incoming channel (simulated worker <- RabbitMQ)
        props.put("mp.messaging.incoming.workerSim.connector", "smallrye-rabbitmq");
        props.put("mp.messaging.incoming.workerSim.exchange.name", "exchange_id_mapper");
        props.put("mp.messaging.incoming.workerSim.exchange.type", "direct");
        props.put("mp.messaging.incoming.workerSim.routing-keys", "routing_id_lookup_with_ctx");
        props.put("mp.messaging.incoming.workerSim.queue.name", "test_worker_queue");

        // Outgoing status events (simulated worker -> RabbitMQ)
        props.put("mp.messaging.outgoing.workerSimStatus.connector", "smallrye-rabbitmq");
        props.put("mp.messaging.outgoing.workerSimStatus.exchange.name", "exchange_id_mapper");
        props.put("mp.messaging.outgoing.workerSimStatus.exchange.type", "direct");
        props.put("mp.messaging.outgoing.workerSimStatus.default-routing-key", "routing_id_lookup_status");

        // Incoming status events (gateway <- RabbitMQ)
        props.put("mp.messaging.incoming.lookupStatus.connector", "smallrye-rabbitmq");
        props.put("mp.messaging.incoming.lookupStatus.exchange.name", "exchange_id_mapper");
        props.put("mp.messaging.incoming.lookupStatus.exchange.type", "direct");
        props.put("mp.messaging.incoming.lookupStatus.routing-keys", "routing_id_lookup_status");
        props.put("mp.messaging.incoming.lookupStatus.queue.name", "queue_id_lookup_status");

        // ArangoDB
        props.put("cache.hosts", arango.getHost() + ":" + arango.getMappedPort(8529));
        props.put("cache.password", ARANGO_ROOT_PASSWORD);
        // Short TTL so the test can also assert on the configured value.
        props.put("cache.ttl-seconds", "120");

        // Short lookup timeout with a fast sweep so the timed-out path can be
        // asserted in-test without long waits.
        props.put("lookup.timeout-seconds", "5");
        props.put("lookup.timeout-sweep-every", "1s");

        return props;
    }

    @Override
    public void stop() {
        if (arango != null) arango.stop();
        if (rabbit != null) rabbit.stop();
    }
}
