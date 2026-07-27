package queue;

import io.quarkus.logging.Log;
import cache.ArangoService;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Fan-in half of the lookup pipeline: consumes the status events that the
 * workers publish (claimed / done / failed) and applies them to the result
 * cache. This is the only place where worker output reaches the database —
 * the workers themselves know nothing about the cache, only the two queues.
 * The writes are safe under redelivery and competing gateway replicas: claims
 * append to a sign-up history, each worker's reply or failure is upserted by
 * worker name (a redelivery replaces the block rather than duplicating it),
 * and the lifecycle state is recomputed on every event — a lookup is done
 * only when every worker that claimed the current occurrence has responded,
 * with at least one success; error when all of them failed.
 */
@ApplicationScoped
public class LookupStatusConsumer {

    @Inject
    ArangoService arangoService;

    @Incoming("lookupStatus")
    @Blocking
    public void onStatusEvent(JsonObject event) {
        String type = event.getString("type");
        String requestHash = event.getString("requestHash");
        if (type == null || requestHash == null) {
            Log.warn("Ignoring malformed status event: " + event);
            return;
        }
        String worker = event.getString("worker", "unknown-worker");
        String at = event.getString("at");
        String requestID = event.getString("requestID");
        String id = event.getString("id");
        String context = event.getString("context");

        switch (type) {
            case "claimed" -> arangoService.recordClaim(requestHash, worker, at, requestID, id, context);
            // Legacy fallback: pre-rename events carried the reply under "results".
            case "done" -> arangoService.recordResults(requestHash,
                    event.getJsonArray("reply", event.getJsonArray("results", new JsonArray())).getList(),
                    worker, at, requestID, id, context);
            case "failed" -> arangoService.recordFailure(requestHash,
                    event.getString("detail"), worker, at, requestID, id, context);
            default -> Log.warn("Ignoring status event of unknown type '" + type
                    + "' for request: " + requestHash);
        }
    }
}
