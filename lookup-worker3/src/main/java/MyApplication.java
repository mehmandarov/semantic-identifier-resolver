
import io.micrometer.core.annotation.Counted;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import model.LookupResultElement;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import queue.QueueHandler;
import queue.StatusPublisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/")
public class MyApplication{

    private static final String WORKER_NAME = "lookup-worker-3";

    @Inject
    QueueHandler queueHandler;

    @Inject
    StatusPublisher statusPublisher;

    /**
     * Liveness check that also introduces the worker: answers pong with the
     * worker's name and the contexts it replies to (worker.contexts).
     */
    @Path("api/ping")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> entryPoint() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", "pong");
        body.put("worker", WORKER_NAME);
        body.put("contexts", queueHandler.handledContexts());
        return body;
    }

    /**
     * Consumes one lookup request and reports its lifecycle back to the
     * gateway as status events: "claimed" as soon as the request is picked
     * up, then "done" with the reply, or "failed" with the failure detail.
     * Requests in contexts this worker does not cover are ignored without a
     * claim — another worker (or the gateway's timeout sweep) owns those.
     * The worker never touches the cache database; the gateway's status
     * consumer owns all cache writes.
     */
    @Incoming("idMapper")
    @Blocking
    @Counted(value = "id_lookup_service_app3", extraTags = {"purpose", "total"})
    @Operation(summary = "CUSTOM: ID lookup service, app3",
            description = "Lookup a certain ID")
    public void idRetriever(JsonObject obj) {
        LookupQueueRequest lookupReq = obj.mapTo(LookupQueueRequest.class);
        if (!queueHandler.handles(lookupReq.context)) {
            System.out.println(WORKER_NAME + ": skipping request " + lookupReq.requestHash
                    + " with unhandled context: " + lookupReq.context);
            return;
        }
        // Announce the pick-up before processing starts, so polls of the
        // results URL can report who is working on the lookup.
        statusPublisher.claimed(lookupReq, WORKER_NAME);
        try {
            // Processing step: the plug-in slot for the use-case specific
            // resolution logic of this worker (see QueueHandler).
            List<LookupResultElement> results = queueHandler.processLookupRequest(lookupReq);
            statusPublisher.done(lookupReq, results, WORKER_NAME);
        } catch (RuntimeException e) {
            statusPublisher.failed(lookupReq, e.getMessage(), WORKER_NAME);
        }
    }
}
