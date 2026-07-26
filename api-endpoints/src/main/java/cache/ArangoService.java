package cache;

import com.arangodb.ArangoDB;
import com.arangodb.ArangoDBException;
import com.arangodb.entity.ArangoDBVersion;
import com.arangodb.entity.BaseDocument;

import com.arangodb.entity.CollectionEntity;
import com.arangodb.model.TtlIndexOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ArangoService {

    static final String CREATED_AT_FIELD = "createdAt";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_IN_PROGRESS = "in_progress";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_TIMED_OUT = "timed-out";

    private final ArangoDB arango;
    private final long ttlSeconds;
    private final String dbName = "idekanin";
    private final String dbCollection = "id_request_cache";
    private CollectionEntity myArangoCollection;

    @Inject
    public ArangoService(final ArangoDB arango,
                         @ConfigProperty(name = "cache.ttl-seconds", defaultValue = "3600") long ttlSeconds) {
        this.arango = arango;
        this.ttlSeconds = ttlSeconds;

        try {
            // Check if the database exists, if not create it.
            if (!arango.getAccessibleDatabases().contains(dbName)){
                arango.createDatabase(dbName);
                System.out.println("Database created: " + dbName);
            } else {
                System.out.println("Using existing database: " + dbName);
            }

            // Check if the collection exists, if not create it.
            if (!arango.db(dbName).getCollections().contains(dbCollection)){
                myArangoCollection = arango.db(dbName).createCollection(dbCollection);
                System.out.println("Collection created: " + dbCollection);
            } else {
                System.out.println("Using existing collection: " + dbCollection);
            }

            // Ensure a TTL index on createdAt so cache documents expire after
            // the configured retention window. ttl<=0 disables the TTL index.
            if (ttlSeconds > 0) {
                arango.db(dbName).collection(dbCollection).ensureTtlIndex(
                        Collections.singleton(CREATED_AT_FIELD),
                        new TtlIndexOptions().expireAfter((int) ttlSeconds));
                System.out.println("TTL index ensured on '" + CREATED_AT_FIELD +
                        "' with expireAfter=" + ttlSeconds + "s");
            } else {
                System.out.println("TTL disabled (cache.ttl-seconds<=0), no TTL index configured.");
            }
        } catch(ArangoDBException e) {
            System.err.println("Failed to create database: " + dbName + " or collection: " + dbCollection + "; " + e.getMessage());
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public ArangoDBVersion getVersion() {
        return arango.getVersion();
    }

    /**
     * Records a freshly accepted lookup request in the cache with status "pending",
     * keyed by the request hash, so the results endpoint can answer polls from the
     * moment the request is accepted. If a document already exists for the hash,
     * it is left as-is: repeated lookups are idempotent and the cached state stands.
     */
    public void recordPendingRequest(LookupQueueRequest request) {
        String key = request.requestHash.toString();
        try {
            if (arango.db(dbName).collection(dbCollection).documentExists(key)) {
                System.out.println("Request already recorded, leaving cache document as-is: " + key);
                return;
            }
            BaseDocument doc = new BaseDocument(key);
            doc.addAttribute("requestID", request.requestID.toString());
            doc.addAttribute("id", request.id);
            doc.addAttribute("context", request.context);
            doc.addAttribute("status", STATUS_PENDING);
            doc.addAttribute(CREATED_AT_FIELD, Instant.now().getEpochSecond());
            arango.db(dbName).collection(dbCollection).insertDocument(doc);
            System.out.println("Recorded pending request: " + key);
        } catch (ArangoDBException e) {
            System.err.println("Failed to record pending request: " + key + "; " + e.getMessage());
        }
    }

    /**
     * Reads the cache document for a lookup request by its request hash.
     * Returns null if no document exists for the key.
     */
    public BaseDocument getRequestDocument(String key) {
        try {
            return arango.db(dbName).collection(dbCollection).getDocument(key, BaseDocument.class);
        } catch (ArangoDBException e) {
            System.err.println("Failed to read request document: " + key + "; " + e.getMessage());
            return null;
        }
    }

    /**
     * Records that a worker has claimed (picked up) a lookup request: appends
     * a {@code {worker, at}} entry to the document's claimedBy list and moves
     * a pending (or timed-out) lookup to in_progress. A claim never regresses
     * a lookup that is already done or in error — the claim entry is still
     * appended, so the full pick-up history stays visible. If the document is
     * gone (e.g. expired by TTL), it is recreated from the event's coordinates.
     */
    public void recordClaim(String requestHash, String worker, String at,
                            String requestID, String id, String context) {
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("worker", worker);
        claim.put("at", at);
        String aql = """
                UPSERT { _key: @key }
                INSERT { _key: @key, requestID: @requestID, id: @id, context: @context,
                         status: "in_progress", createdAt: @now, claimedBy: [ @claim ] }
                UPDATE { status: OLD.status IN ["pending", "timed-out"] ? "in_progress" : OLD.status,
                         claimedBy: APPEND(NOT_NULL(OLD.claimedBy, []), [ @claim ]) }
                IN @@coll
                """;
        try {
            arango.db(dbName).query(aql, Void.class,
                    baseBindVars(requestHash, requestID, id, context, Map.of("claim", claim)));
            System.out.println("Recorded claim by " + worker + " for request: " + requestHash);
        } catch (ArangoDBException e) {
            System.err.println("Failed to record claim for request: " + requestHash + "; " + e.getMessage());
        }
    }

    /**
     * Writes a worker's results for a lookup request and marks it done.
     * A late result deliberately overwrites a timed-out (or error) state —
     * the resolution is still useful to cache — and clears any stale failure
     * detail. If the document is gone, it is recreated.
     */
    public void recordResults(String requestHash, List<?> results, String worker, String at,
                              String requestID, String id, String context) {
        String aql = """
                UPSERT { _key: @key }
                INSERT { _key: @key, requestID: @requestID, id: @id, context: @context,
                         status: "done", createdAt: @now, results: @results,
                         resolvedBy: @worker, resolvedAt: @at }
                UPDATE { status: "done", results: @results,
                         resolvedBy: @worker, resolvedAt: @at, detail: null }
                IN @@coll OPTIONS { keepNull: false }
                """;
        try {
            arango.db(dbName).query(aql, Void.class,
                    baseBindVars(requestHash, requestID, id, context,
                            Map.of("results", results, "worker", worker, "at", at)));
            System.out.println("Recorded results from " + worker + " for request: " + requestHash);
        } catch (ArangoDBException e) {
            System.err.println("Failed to record results for request: " + requestHash + "; " + e.getMessage());
        }
    }

    /**
     * Marks a lookup request as failed with the worker's failure detail. A
     * failure never overwrites a lookup that is already done (another worker
     * may have answered successfully). If the document is gone, it is recreated.
     */
    public void recordFailure(String requestHash, String detail, String worker, String at,
                              String requestID, String id, String context) {
        String aql = """
                UPSERT { _key: @key }
                INSERT { _key: @key, requestID: @requestID, id: @id, context: @context,
                         status: "error", createdAt: @now, detail: @detail,
                         resolvedBy: @worker, resolvedAt: @at }
                UPDATE { status: OLD.status == "done" ? OLD.status : "error",
                         detail: OLD.status == "done" ? OLD.detail : @detail,
                         resolvedBy: OLD.status == "done" ? OLD.resolvedBy : @worker,
                         resolvedAt: OLD.status == "done" ? OLD.resolvedAt : @at }
                IN @@coll
                """;
        try {
            arango.db(dbName).query(aql, Void.class,
                    baseBindVars(requestHash, requestID, id, context,
                            Map.of("detail", detail == null ? "Unknown processing failure." : detail,
                                    "worker", worker, "at", at)));
            System.out.println("Recorded failure from " + worker + " for request: " + requestHash);
        } catch (ArangoDBException e) {
            System.err.println("Failed to record failure for request: " + requestHash + "; " + e.getMessage());
        }
    }

    /**
     * Marks every lookup that is still pending or in_progress after the given
     * timeout window (measured from createdAt) as timed-out. Returns the number
     * of documents that were marked. Invoked periodically by the timeout sweeper.
     */
    public long markTimedOutRequests(long timeoutSeconds) {
        long cutoff = Instant.now().getEpochSecond() - timeoutSeconds;
        String aql = """
                FOR d IN @@coll
                  FILTER d.status IN ["pending", "in_progress"]
                  FILTER d.createdAt != null AND d.createdAt <= @cutoff
                  UPDATE d WITH { status: "timed-out",
                                  detail: CONCAT("Lookup timed out after ", @timeoutSeconds,
                                                 " seconds without completion.") }
                  IN @@coll
                  RETURN NEW._key
                """;
        try {
            List<String> marked = arango.db(dbName)
                    .query(aql, String.class,
                            Map.of("@coll", dbCollection, "cutoff", cutoff, "timeoutSeconds", timeoutSeconds))
                    .asListRemaining();
            return marked.size();
        } catch (ArangoDBException e) {
            System.err.println("Failed to mark timed-out requests; " + e.getMessage());
            return 0;
        }
    }

    /**
     * Common bind variables for the status-event UPSERTs: the document key,
     * the request coordinates used by the INSERT (recreate) branch, and the
     * TTL anchor for a recreated document.
     */
    private Map<String, Object> baseBindVars(String requestHash, String requestID, String id,
                                             String context, Map<String, Object> extra) {
        Map<String, Object> bindVars = new LinkedHashMap<>();
        bindVars.put("@coll", dbCollection);
        bindVars.put("key", requestHash);
        bindVars.put("requestID", requestID);
        bindVars.put("id", id);
        bindVars.put("context", context);
        bindVars.put("now", Instant.now().getEpochSecond());
        bindVars.putAll(extra);
        return bindVars;
    }

}
