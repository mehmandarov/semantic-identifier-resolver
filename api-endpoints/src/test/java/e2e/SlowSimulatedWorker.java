package e2e;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.time.Instant;

/**
 * Test-only mock of a slow worker: claims lookups in the {@code SLOW}
 * context, then sleeps past the gateway's (test-configured) lookup timeout
 * before replying — so the timeout sweep fires mid-processing and the reply
 * arrives late. Exercises the full in_progress → timed-out → accept-late
 * path through the real queues, mirroring the FAKE processing delay knob of
 * production worker 2 (worker.fake-processing-delay-ms).
 */
@ApplicationScoped
public class SlowSimulatedWorker {

    static final String WORKER_NAME = "e2e-slow-worker";
    static final String SLOW_CONTEXT = "SLOW";

    @Inject
    @Channel("workerSimStatus")
    Emitter<JsonObject> statusEmitter;

    @ConfigProperty(name = "e2e.slow-worker.delay-ms", defaultValue = "12000")
    long delayMs;

    @Incoming("workerSimSlow")
    @Blocking
    public void onLookup(JsonObject obj) throws InterruptedException {
        LookupQueueRequest req = obj.mapTo(LookupQueueRequest.class);
        if (!SLOW_CONTEXT.equalsIgnoreCase(req.context)) {
            // Mirrors production context filtering: not ours, no claim.
            return;
        }
        statusEmitter.send(statusEvent("claimed", req));
        Log.info("Slow worker claimed " + req.requestHash + ", sleeping " + delayMs + " ms");
        Thread.sleep(delayMs);
        JsonArray reply = new JsonArray()
                .add(new JsonObject()
                        .put("id", req.id)
                        .put("context", req.context)
                        .put("relationship", "same-as"));
        statusEmitter.send(statusEvent("done", req).put("reply", reply));
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
