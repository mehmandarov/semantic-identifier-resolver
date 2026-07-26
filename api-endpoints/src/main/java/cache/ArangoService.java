package cache;

import com.arangodb.ArangoCursor;
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

import java.io.IOException;
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

            // Check if the collection exists, if not create it. Note:
            // getCollections() answers CollectionEntity objects, so the
            // existence check must compare names, not the raw entities.
            boolean collectionExists = arango.db(dbName).getCollections().stream()
                    .map(CollectionEntity::getName)
                    .anyMatch(dbCollection::equals);
            if (!collectionExists) {
                arango.db(dbName).createCollection(dbCollection);
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
        try (ArangoCursor<Void> ignored = arango.db(dbName).query(aql, Void.class,
                baseBindVars(requestHash, requestID, id, context, Map.of("claim", claim)))) {
            System.out.println("Recorded claim by " + worker + " for request: " + requestHash);
        } catch (ArangoDBException | IOException e) {
            System.err.println("Failed to record claim for request: " + requestHash + "; " + e.getMessage());
        }
    }

    /**
     * Cache-aware intake decision for one lookup occurrence. Ensures a cache
     * document exists for the request and decides whether the request must
     * be published to the workers: a fresh lookup is recorded as pending and
     * published; a lookup that previously timed out or failed — or any cached
     * lookup when the caller demands a hard refresh — is reset to pending
     * (fresh requestID and createdAt; stale detail, replies and provenance
     * cleared — the claim history stays) and re-published; a lookup that is
     * already pending, in progress or done stands as cached, and no new work
     * is queued.
     */
    public boolean prepareRequestForPublish(LookupQueueRequest request, boolean hardRefresh) {
        String key = request.requestHash.toString();
        BaseDocument existing = getRequestDocument(key);
        if (existing == null) {
            recordPendingRequest(request);
            return true;
        }
        String status = String.valueOf(existing.getAttribute("status"));
        boolean retryable = STATUS_TIMED_OUT.equals(status) || STATUS_ERROR.equals(status);
        if (hardRefresh || retryable) {
            String aql = """
                    UPDATE @key WITH { status: "pending", requestID: @requestID,
                                       createdAt: @now, detail: null,
                                       resultsByWorker: null, resolvedBy: null, resolvedAt: null }
                    IN @@coll OPTIONS { keepNull: false }
                    """;
            try (ArangoCursor<Void> ignored = arango.db(dbName).query(aql, Void.class,
                    Map.of("@coll", dbCollection, "key", key,
                            "requestID", request.requestID.toString(),
                            "now", Instant.now().getEpochSecond()))) {
                System.out.println("Reset " + status + " lookup for "
                        + (hardRefresh ? "hard refresh" : "retry") + ": " + key);
            } catch (ArangoDBException | IOException e) {
                System.err.println("Failed to reset lookup for reprocessing: " + key + "; " + e.getMessage());
            }
            return true;
        }
        System.out.println("Lookup already " + status + ", cached state stands: " + key);
        return false;
    }

    /**
     * Merges a worker's reply into the results of a lookup request and marks
     * it done. Replies are aggregated per worker: each entry in the document's
     * resultsByWorker list is one {@code {worker, at, reply}} block, so answers
     * from several workers converge without overwriting each other, and a
     * redelivered reply replaces the worker's previous block instead of
     * duplicating it. A late reply deliberately overwrites a timed-out (or
     * error) state — the resolution is still useful to cache — and clears any
     * stale failure detail. If the document is gone, it is recreated.
     */
    public void recordResults(String requestHash, List<?> reply, String worker, String at,
                              String requestID, String id, String context) {
        String aql = """
                LET replyBlock = { worker: @worker, at: @at, reply: @reply }
                UPSERT { _key: @key }
                INSERT { _key: @key, requestID: @requestID, id: @id, context: @context,
                         status: "done", createdAt: @now, resultsByWorker: [ replyBlock ],
                         resolvedBy: @worker, resolvedAt: @at }
                UPDATE { status: "done",
                         resultsByWorker: APPEND(
                             (FOR r IN NOT_NULL(OLD.resultsByWorker, []) FILTER r.worker != @worker RETURN r),
                             [ replyBlock ]),
                         resolvedBy: @worker, resolvedAt: @at, detail: null }
                IN @@coll OPTIONS { keepNull: false }
                """;
        try (ArangoCursor<Void> ignored = arango.db(dbName).query(aql, Void.class,
                baseBindVars(requestHash, requestID, id, context,
                        Map.of("reply", reply, "worker", worker, "at", at)))) {
            System.out.println("Recorded reply from " + worker + " for request: " + requestHash);
        } catch (ArangoDBException | IOException e) {
            System.err.println("Failed to record reply for request: " + requestHash + "; " + e.getMessage());
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
        try (ArangoCursor<Void> ignored = arango.db(dbName).query(aql, Void.class,
                baseBindVars(requestHash, requestID, id, context,
                        Map.of("detail", detail == null ? "Unknown processing failure." : detail,
                                "worker", worker, "at", at)))) {
            System.out.println("Recorded failure from " + worker + " for request: " + requestHash);
        } catch (ArangoDBException | IOException e) {
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
        try (ArangoCursor<String> cursor = arango.db(dbName).query(aql, String.class,
                Map.of("@coll", dbCollection, "cutoff", cutoff, "timeoutSeconds", timeoutSeconds))) {
            return cursor.asListRemaining().size();
        } catch (ArangoDBException | IOException e) {
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
