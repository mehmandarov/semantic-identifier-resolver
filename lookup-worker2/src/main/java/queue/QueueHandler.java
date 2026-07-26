package queue;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import model.LookupResultElement;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The processing step of the worker: the plug-in slot where the resolution
 * logic goes. This worker covers the {@code TAG} context and answers with
 * the asset the tag belongs to ({@code part-of}): the system, as a small
 * hardcoded demo mapping standing in for a real plant breakdown structure.
 * Together with worker 1 (which answers the same context with {@code same-as}
 * identifiers) it demonstrates the fan-in of several workers on one request.
 */
@ApplicationScoped
public class QueueHandler {

    /**
     * Contexts this worker answers, from the worker.contexts property:
     * which contexts a deployment covers is configuration, while how ids
     * in those contexts are resolved is this class's plug-in logic.
     */
    private final Set<String> handledContexts;

    /**
     * FAKE processing delay — dev/demo ONLY, never for production. Sleeps
     * this many milliseconds inside the processing step (after the claim),
     * simulating a slow source so the lookup lifecycle can be watched in
     * the tracing UI. Values to try against the gateway's 60s timeout:
     * 15000 (finishes in time) or 90000 (lookup answers 504 timed-out, the
     * late reply then overwrites it — the accept-late policy live).
     */
    private final long fakeProcessingDelayMs;

    @Inject
    public QueueHandler(@ConfigProperty(name = "worker.contexts") List<String> contexts,
                        @ConfigProperty(name = "worker.fake-processing-delay-ms", defaultValue = "0")
                        long fakeProcessingDelayMs) {
        this.handledContexts = contexts.stream()
                .map(c -> c.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
        this.fakeProcessingDelayMs = fakeProcessingDelayMs;
        if (fakeProcessingDelayMs > 0) {
            Log.warn("FAKE processing delay is active: every lookup is slowed by "
                    + fakeProcessingDelayMs + " ms. Dev/demo setting only — never use in production.");
        }
    }

    /** Demo stand-in for this worker's source: tag -> what the tag is part of. */
    private static final Map<String, List<LookupResultElement>> TAG_PARENTS = Map.of(
            "A-24HA001", List.of(new LookupResultElement("SYSTEM-24", "SYSTEM", "part-of")),
            "A-24HA002", List.of(new LookupResultElement("SYSTEM-24", "SYSTEM", "part-of")));

    /**
     * Whether this worker's source can answer lookups in the given context.
     * Requests in other contexts are ignored without a claim, leaving them
     * to the workers that cover them.
     */
    public boolean handles(String context) {
        return context != null && handledContexts.contains(context.trim().toUpperCase());
    }

    public Set<String> handledContexts() {
        return handledContexts;
    }

    public List<LookupResultElement> processLookupRequest(LookupQueueRequest lookupReq) {
        Log.info("Looked up ID: " + lookupReq.id + " and context: " + lookupReq.context
                + " (requestID: " + lookupReq.requestID + ", requestHash: " + lookupReq.requestHash + ")");
        applyFakeDelay(lookupReq);
        // Unknown tags answer an empty reply: the source was consulted and
        // had nothing — which is itself a cacheable answer.
        return TAG_PARENTS.getOrDefault(normalize(lookupReq.id), List.of());
    }

    /** FAKE dev/demo-only slowdown; see {@link #fakeProcessingDelayMs}. */
    private void applyFakeDelay(LookupQueueRequest lookupReq) {
        if (fakeProcessingDelayMs <= 0) {
            return;
        }
        Log.warn("FAKE delay: sleeping " + fakeProcessingDelayMs + " ms before answering request "
                + lookupReq.requestHash);
        try {
            Thread.sleep(fakeProcessingDelayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during FAKE processing delay", e);
        }
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toUpperCase();
    }
}
