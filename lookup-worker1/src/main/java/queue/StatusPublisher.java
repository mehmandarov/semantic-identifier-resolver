package queue;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import model.LookupResultElement;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The worker's only output channel: lookup status events published to the
 * shared status queue. The gateway consumes these events and maintains the
 * result cache, so workers stay unaware of the cache and its database.
 * <p>
 * Three event types cover the lifecycle of one processing attempt:
 * <ul>
 *     <li>{@code claimed} — sent before processing starts, so the gateway can
 *     record which worker(s) picked the request up;</li>
 *     <li>{@code done} — carries the worker's reply: its resolved result
 *     elements;</li>
 *     <li>{@code failed} — carries the failure detail of an unsuccessful one.</li>
 * </ul>
 * Every event carries the request coordinates (requestHash, requestID, id,
 * context), the worker name and a timestamp, so the consumer can rebuild the
 * cache document even if it was expired in the meantime.
 */
@ApplicationScoped
public class StatusPublisher {

    @Inject
    @Channel("lookupStatus")
    Emitter<JsonObject> statusEmitter;

    public void claimed(LookupQueueRequest request, String workerName) {
        JsonObject event = statusEvent("claimed", request, workerName);
        Context traceContext = Context.current();
        // Emit from outside the consuming message's context: with tracing
        // active, an emission made on the @Blocking consumer's context is
        // only flushed once the handler returns — which would delay the
        // claim until after processing, defeating its purpose for slow
        // lookups. A pool thread publishes immediately; the trace context
        // is carried along so the event stays in the lookup's trace.
        CompletableFuture.runAsync(() -> {
            try (Scope ignored = traceContext.makeCurrent()) {
                statusEmitter.send(event);
            }
        });
    }

    public void done(LookupQueueRequest request, List<LookupResultElement> reply, String workerName) {
        JsonArray replyArray = new JsonArray();
        for (LookupResultElement element : reply) {
            replyArray.add(new JsonObject()
                    .put("id", element.id)
                    .put("context", element.context)
                    .put("relationship", element.relationship));
        }
        statusEmitter.send(statusEvent("done", request, workerName).put("reply", replyArray));
    }

    public void failed(LookupQueueRequest request, String detail, String workerName) {
        statusEmitter.send(statusEvent("failed", request, workerName)
                .put("detail", detail == null ? "Unknown processing failure." : detail));
    }

    private static JsonObject statusEvent(String type, LookupQueueRequest request, String workerName) {
        return new JsonObject()
                .put("type", type)
                .put("requestID", request.requestID.toString())
                .put("requestHash", request.requestHash.toString())
                .put("id", request.id)
                .put("context", request.context)
                .put("worker", workerName)
                .put("at", Instant.now().toString());
    }
}
