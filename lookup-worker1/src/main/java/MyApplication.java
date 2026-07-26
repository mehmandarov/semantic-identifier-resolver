
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

import java.net.URISyntaxException;
import java.util.List;

@ApplicationScoped
@Path("/")
public class MyApplication{

    private static final String WORKER_NAME = "lookup-worker-1";

    @Inject
    QueueHandler queueHandler;

    @Inject
    StatusPublisher statusPublisher;

    @Path("api/ping")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String entryPoint() throws URISyntaxException {
        return "lookup-worker1: Hai there!";
    }

    /**
     * Consumes one lookup request and reports its lifecycle back to the
     * gateway as status events: "claimed" as soon as the request is picked
     * up, then "done" with the results, or "failed" with the failure detail.
     * The worker never touches the cache database; the gateway's status
     * consumer owns all cache writes.
     */
    @Incoming("idMapper")
    @Blocking
    @Counted(value = "id_lookup_service_app1", extraTags = {"purpose", "total"})
    @Operation(summary = "CUSTOM: ID lookup service, app1",
            description = "Lookup a certain ID")
    public void idRetriever(JsonObject obj) {
        LookupQueueRequest lookupReq = obj.mapTo(LookupQueueRequest.class);
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
