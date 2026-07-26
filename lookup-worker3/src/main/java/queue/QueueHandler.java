package queue;

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
 * logic goes. This worker covers the {@code EPC_DESCRIPTOR} context — the
 * identifiers assigned by the EPC contractor — and answers with the serial
 * number that is equivalent to the descriptor ({@code same-as}). The source
 * is a small hardcoded demo mapping standing in for the contractor's system.
 */
@ApplicationScoped
public class QueueHandler {

    /**
     * Contexts this worker answers, from the worker.contexts property:
     * which contexts a deployment covers is configuration, while how ids
     * in those contexts are resolved is this class's plug-in logic.
     */
    private final Set<String> handledContexts;

    @Inject
    public QueueHandler(@ConfigProperty(name = "worker.contexts") List<String> contexts) {
        this.handledContexts = contexts.stream()
                .map(c -> c.trim().toUpperCase())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Demo stand-in for this worker's source: EPC descriptor -> serial number. */
    private static final Map<String, List<LookupResultElement>> DESCRIPTOR_SERIALS = Map.of(
            "EJ101A", List.of(new LookupResultElement("SN-1042-77", "SERIAL", "same-as")),
            "EJ101B", List.of(new LookupResultElement("SN-1042-78", "SERIAL", "same-as")));

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
        System.out.println("*************Looked up ID: " + lookupReq.id + " and context: " + lookupReq.context
                + " (requestID: " + lookupReq.requestID + ", requestHash: " + lookupReq.requestHash + ")");
        // Unknown descriptors answer an empty reply: the source was consulted
        // and had nothing — which is itself a cacheable answer.
        return DESCRIPTOR_SERIALS.getOrDefault(normalize(lookupReq.id), List.of());
    }

    private static String normalize(String id) {
        return id == null ? "" : id.trim().toUpperCase();
    }
}
