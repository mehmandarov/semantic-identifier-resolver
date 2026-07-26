package e2e;

import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.time.Instant;

/**
 * Test-only stand-in for lookup-worker1: consumes lookup requests from
 * RabbitMQ and publishes status events (claimed, then done) back to the
 * status queue, mirroring the production worker. Like the production worker,
 * it never touches the cache database — the gateway's status consumer owns
 * all cache writes. Kept in this module so the whole POST -> queue -> worker
 * -> status events -> cache -> GET flow can be exercised inside a single JVM.
 */
@ApplicationScoped
public class SimulatedWorker {

    static final String WORKER_NAME = "e2e-test-worker";

    @Inject
    @Channel("workerSimStatus")
    Emitter<JsonObject> statusEmitter;

    @Incoming("workerSim")
    @Blocking
    public void onLookup(JsonObject obj) {
        LookupQueueRequest req = obj.mapTo(LookupQueueRequest.class);

        // Announce the pick-up first, so the gateway can record who claimed it.
        statusEmitter.send(statusEvent("claimed", req));

        // Echo the input tuple as a single-element result, matching the
        // production worker's v1 behaviour, and report the lookup as done.
        JsonArray results = new JsonArray()
                .add(new JsonObject().put("id", req.id).put("context", req.context));
        statusEmitter.send(statusEvent("done", req).put("results", results));
    }

    private static JsonObject statusEvent(String type, LookupQueueRequest req) {
        return new JsonObject()
                .put("type", type)
                .put("requestID", req.requestID.toString())
                .put("requestHash", req.requestHash.toString())
                .put("id", req.id)
                .put("context", req.context)
                .put("worker", WORKER_NAME)
                .put("at", Instant.now().toString());
    }
}
