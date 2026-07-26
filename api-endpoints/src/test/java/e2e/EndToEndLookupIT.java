package e2e;

import cache.ArangoService;
import com.arangodb.ArangoDB;
import com.arangodb.entity.BaseDocument;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.junit.jupiter.api.Test;
import utils.UUIDv5;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the lookup pipeline.
 * <p>
 * Exercises the full flow:
 * <ol>
 *     <li>Client POSTs a lookup request -> 202 Accepted with a Location header.</li>
 *     <li>Gateway records the request as pending in ArangoDB and publishes it to RabbitMQ.</li>
 *     <li>An in-test worker consumes from RabbitMQ and publishes claimed/done
 *         status events back to the status queue.</li>
 *     <li>The gateway's status consumer applies the events to ArangoDB — the
 *         worker itself never touches the cache.</li>
 *     <li>Client polls the results URL -> eventually 200 OK with the resolved results.</li>
 *     <li>A repeat GET is served from the cached document (same requestHash).</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(IntegrationEnvResource.class)
class EndToEndLookupIT {

    @Inject ArangoDB arango;
    @Inject ArangoService arangoService;

    @Inject
    @Channel("workerSimStatus")
    Emitter<JsonObject> statusEmitter;

    @Test
    void fullLookupFlow_returnsCachedResults() {
        Response accepted = given()
                .contentType("application/json")
                .body(Map.of("id", "A-24HA001", "context", "TAG"))
                .when().post("/api/lookup")
                .then()
                    .statusCode(202)
                    .header("Location", notNullValue())
                    .body("requestHash", notNullValue())
                    .body("requestID", notNullValue())
                    .extract().response();

        String location = accepted.getHeader("Location");
        String requestHash = accepted.jsonPath().getString("requestHash");
        assertNotNull(location);
        assertTrue(location.endsWith("/api/cache/" + requestHash),
                "Location should point at the results URL for the requestHash");

        // Poll the results endpoint until the simulated worker has written results.
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().when().get("/api/cache/{k}", requestHash)
                        .then().statusCode(200)
                        .body("status", equalTo("done"))
                        .body("id", equalTo("A-24HA001"))
                        .body("context", equalTo("TAG"))
                        .body("results.size()", equalTo(1))
                        .body("results[0].id", equalTo("A-24HA001"))
                        .body("results[0].context", equalTo("TAG"))
                        .body("results[0].relationship", equalTo("same-as"))
                        .body("resolvedBy", equalTo(SimulatedWorker.WORKER_NAME))
                        // Pick-up tracking: the claim event was applied too.
                        .body("claimedBy.size()", greaterThanOrEqualTo(1))
                        .body("claimedBy[0].worker", equalTo(SimulatedWorker.WORKER_NAME)));

        // A second GET should be served straight from the cached document,
        // returning identical content without touching the queue.
        given().when().get("/api/cache/{k}", requestHash)
                .then().statusCode(200)
                .body("requestHash", equalTo(requestHash))
                .body("status", equalTo("done"));
    }

    @Test
    void repeatPost_isIdempotent_reusesRequestHash() {
        Map<String, String> body = Map.of("id", "B-99XX042", "context", "ASSET");

        String hash1 = given().contentType("application/json").body(body)
                .when().post("/api/lookup")
                .then().statusCode(202).extract().jsonPath().getString("requestHash");

        await().atMost(Duration.ofSeconds(30))
                .until(() -> given().when().get("/api/cache/{k}", hash1)
                        .then().extract().statusCode() == 200);

        String hash2 = given().contentType("application/json").body(body)
                .when().post("/api/lookup")
                .then().statusCode(202).extract().jsonPath().getString("requestHash");

        assertEquals(hash1, hash2, "Same (id, context) must resolve to the same requestHash");

        // The cache document was left untouched by the second POST — still done.
        given().when().get("/api/cache/{k}", hash2)
                .then().statusCode(200).body("status", equalTo("done"));
    }

    @Test
    void post_blankId_returns400() {
        given().contentType("application/json")
                .body(Map.of("id", "  ", "context", "TAG"))
                .when().post("/api/lookup")
                .then().statusCode(400).body("error", notNullValue());
    }

    @Test
    void get_unknownHash_returns404() {
        given().when().get("/api/cache/00000000-0000-0000-0000-000000000000")
                .then().statusCode(404).body("error", notNullValue());
    }

    @Test
    void ttlIsAppliedToCachedDocuments() {
        // Sanity-check the TTL configuration wiring: the service picked up
        // the value from the test resource, and every cache document carries
        // a createdAt attribute for the TTL background job to key off.
        assertEquals(120L, arangoService.getTtlSeconds());

        String hash = given().contentType("application/json")
                .body(Map.of("id", "C-TTL-001", "context", "TAG"))
                .when().post("/api/lookup")
                .then().statusCode(202).extract().jsonPath().getString("requestHash");

        BaseDocument doc = arango.db("idekanin").collection("id_request_cache")
                .getDocument(hash, BaseDocument.class);
        assertNotNull(doc, "Pending document should exist right after POST");
        Object createdAt = doc.getAttribute("createdAt");
        assertNotNull(createdAt, "createdAt must be populated so the TTL index can expire the doc");

        // Confirm the TTL index really exists on the collection.
        var indexes = arango.db("idekanin").collection("id_request_cache").getIndexes();
        boolean hasTtl = indexes.stream()
                .anyMatch(i -> "ttl".equalsIgnoreCase(String.valueOf(i.getType())));
        assertTrue(hasTtl, "A TTL index must be present on the cache collection");
    }

    // ---------------------------------------------------------------------
    // Tests below seed the cache directly so they never publish to RabbitMQ
    // and are therefore never overtaken by the in-test worker consumer.
    // ---------------------------------------------------------------------

    /**
     * Reproduces the read-side branch called out in the project write-up:
     * "no worker writes results back yet [...] a poll for a known request
     * answers 202 (pending)". We drive the pending path directly through
     * {@link ArangoService#recordPendingRequest} to guarantee no worker
     * ever fills the document, then poll and assert the 202.
     */
    @Test
    void get_knownRequestWithNoResultsYet_returns202Pending() {
        String id = "P-PENDING-001";
        String ctx = "TAG";
        UUID hash = UUIDv5.fromUTF8(id.toUpperCase() + "_" + ctx.toUpperCase());
        LookupQueueRequest req = new LookupQueueRequest(
                id, ctx, UUID.randomUUID().toString(), hash.toString());

        arangoService.recordPendingRequest(req);

        given().when().get("/api/cache/{k}", hash.toString())
                .then().statusCode(202)
                .body("status", equalTo("pending"))
                .body("id", equalTo(id))
                .body("context", equalTo(ctx))
                .body("requestHash", equalTo(hash.toString()));
    }

    @Test
    void recordPendingRequest_isIdempotentAtDbLevel() {
        String id = "P-IDEMP-002";
        String ctx = "TAG";
        UUID hash = UUIDv5.fromUTF8(id.toUpperCase() + "_" + ctx.toUpperCase());
        LookupQueueRequest req = new LookupQueueRequest(
                id, ctx, UUID.randomUUID().toString(), hash.toString());

        arangoService.recordPendingRequest(req);
        BaseDocument first = arango.db("idekanin").collection("id_request_cache")
                .getDocument(hash.toString(), BaseDocument.class);
        assertNotNull(first);
        Object firstCreatedAt = first.getAttribute("createdAt");
        String firstRev = first.getRevision();

        // A repeated record for the same key must be a no-op — same rev,
        // same createdAt (TTL is anchored to the first entry).
        arangoService.recordPendingRequest(req);
        BaseDocument second = arango.db("idekanin").collection("id_request_cache")
                .getDocument(hash.toString(), BaseDocument.class);
        assertEquals(firstRev, second.getRevision(),
                "recordPendingRequest must not overwrite an existing document");
        assertEquals(firstCreatedAt, second.getAttribute("createdAt"),
                "TTL anchor must not shift when a duplicate POST is recorded");
    }

    @Test
    void claimedRequest_reportsInProgress_withClaimedBy() {
        String id = "P-CLAIM-003";
        String ctx = "TAG";
        UUID hash = UUIDv5.fromUTF8(id.toUpperCase() + "_" + ctx.toUpperCase());
        LookupQueueRequest req = new LookupQueueRequest(
                id, ctx, UUID.randomUUID().toString(), hash.toString());
        arangoService.recordPendingRequest(req);

        // A worker claims the request: pending -> in_progress, claim recorded.
        arangoService.recordClaim(hash.toString(), "test-worker-A", Instant.now().toString(),
                req.requestID.toString(), id, ctx);

        given().when().get("/api/cache/{k}", hash.toString())
                .then().statusCode(202)
                .body("status", equalTo("in_progress"))
                .body("claimedBy.size()", equalTo(1))
                .body("claimedBy[0].worker", equalTo("test-worker-A"))
                .body("claimedBy[0].at", notNullValue());
    }

    @Test
    void staleRequest_isMarkedTimedOut_andAnswers504() {
        // Seed a pending document whose createdAt is already older than the
        // configured 5s lookup timeout (but far below the 120s cache TTL), so
        // the 1s sweep marks it timed-out without the test having to idle.
        String key = UUID.randomUUID().toString();
        BaseDocument doc = new BaseDocument(key);
        doc.addAttribute("requestID", UUID.randomUUID().toString());
        doc.addAttribute("id", "T-TIMEOUT-001");
        doc.addAttribute("context", "TAG");
        doc.addAttribute("status", "pending");
        doc.addAttribute("createdAt", Instant.now().getEpochSecond() - 10);
        arango.db("idekanin").collection("id_request_cache").insertDocument(doc);

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().when().get("/api/cache/{k}", key)
                        .then().statusCode(504)
                        .body("status", equalTo("timed-out"))
                        .body("detail", notNullValue()));
    }

    @Test
    void lateResult_overwritesTimedOut_andClearsDetail() {
        // Accept-late policy: a worker's result arriving after the timeout
        // sweep still resolves the lookup and clears the stale failure detail.
        String key = UUID.randomUUID().toString();
        String requestID = UUID.randomUUID().toString();
        BaseDocument doc = new BaseDocument(key);
        doc.addAttribute("requestID", requestID);
        doc.addAttribute("id", "T-LATE-002");
        doc.addAttribute("context", "TAG");
        doc.addAttribute("status", "timed-out");
        doc.addAttribute("detail", "Lookup timed out after 5 seconds without completion.");
        doc.addAttribute("createdAt", Instant.now().getEpochSecond());
        arango.db("idekanin").collection("id_request_cache").insertDocument(doc);

        arangoService.recordResults(key, List.of(Map.of("id", "T-LATE-002", "context", "TAG")),
                "late-worker", Instant.now().toString(), requestID, "T-LATE-002", "TAG");

        given().when().get("/api/cache/{k}", key)
                .then().statusCode(200)
                .body("status", equalTo("done"))
                .body("results[0].id", equalTo("T-LATE-002"))
                .body("resolvedBy", equalTo("late-worker"))
                .body("detail", nullValue());
    }

    @Test
    void failedEvent_doesNotOverwriteDone() {
        // With several workers on one request, a failure reported after a
        // successful resolution must not clobber the cached results.
        String key = UUID.randomUUID().toString();
        String requestID = UUID.randomUUID().toString();
        String at = Instant.now().toString();
        arangoService.recordResults(key, List.of(Map.of("id", "M-MULTI-004", "context", "TAG")),
                "worker-ok", at, requestID, "M-MULTI-004", "TAG");
        arangoService.recordFailure(key, "source unreachable", "worker-broken", at,
                requestID, "M-MULTI-004", "TAG");

        given().when().get("/api/cache/{k}", key)
                .then().statusCode(200)
                .body("status", equalTo("done"))
                .body("resolvedBy", equalTo("worker-ok"));
    }

    @Test
    void failedEvent_throughQueue_marksLookupError_andAnswers500() {
        // Drives the claimed -> failed path through the real status queue and
        // the gateway's status consumer, preceded by poison events (malformed,
        // unknown type) that the consumer must ignore without falling over.
        String id = "F-FAIL-005";
        String ctx = "TAG";
        UUID hash = UUIDv5.fromUTF8(id.toUpperCase() + "_" + ctx.toUpperCase());
        LookupQueueRequest req = new LookupQueueRequest(
                id, ctx, UUID.randomUUID().toString(), hash.toString());
        arangoService.recordPendingRequest(req);

        statusEmitter.send(new JsonObject().put("garbage", "no type, no requestHash"));
        statusEmitter.send(statusEvent("bogus-type", req, "failing-worker"));
        statusEmitter.send(statusEvent("claimed", req, "failing-worker"));
        statusEmitter.send(statusEvent("failed", req, "failing-worker")
                .put("detail", "upstream source exploded"));

        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> given().when().get("/api/cache/{k}", hash.toString())
                        .then().statusCode(500)
                        .body("status", equalTo("error"))
                        .body("detail", equalTo("upstream source exploded"))
                        .body("resolvedBy", equalTo("failing-worker"))
                        .body("claimedBy[0].worker", equalTo("failing-worker")));
    }

    private static JsonObject statusEvent(String type, LookupQueueRequest req, String worker) {
        return new JsonObject()
                .put("type", type)
                .put("requestID", req.requestID.toString())
                .put("requestHash", req.requestHash.toString())
                .put("id", req.id)
                .put("context", req.context)
                .put("worker", worker)
                .put("at", Instant.now().toString());
    }

    @Test
    void get_documentInErrorStatus_returns500WithDetail() {
        String key = UUID.randomUUID().toString();
        BaseDocument doc = new BaseDocument(key);
        doc.addAttribute("requestID", UUID.randomUUID().toString());
        doc.addAttribute("id", "E-ERR-003");
        doc.addAttribute("context", "TAG");
        doc.addAttribute("status", "error");
        doc.addAttribute("detail", "upstream source unreachable");
        doc.addAttribute("resolvedBy", "e2e-test-seed");
        doc.addAttribute("resolvedAt", Instant.now().toString());
        doc.addAttribute("createdAt", Instant.now().getEpochSecond());
        arango.db("idekanin").collection("id_request_cache").insertDocument(doc);

        given().when().get("/api/cache/{k}", key)
                .then().statusCode(500)
                .body("status", equalTo("error"))
                .body("detail", equalTo("upstream source unreachable"))
                .body("resolvedBy", equalTo("e2e-test-seed"));
    }

    @Test
    void post_blankContext_returns400() {
        given().contentType("application/json")
                .body(Map.of("id", "X-1", "context", ""))
                .when().post("/api/lookup")
                .then().statusCode(400).body("error", notNullValue());
    }

    @Test
    void post_emptyJsonBody_returns400() {
        given().contentType("application/json")
                .body("{}")
                .when().post("/api/lookup")
                .then().statusCode(400).body("error", notNullValue());
    }

    @Test
    void ping_returnsPong() {
        given().when().get("/api/ping")
                .then().statusCode(200)
                .body(equalTo("api-endpoints: Hai there! PONG."));
    }

    @Test
    void cacheAllEndpoint_returns501NotImplemented() {
        // Contract check: the /cache/all endpoint is a documented placeholder.
        // If someone ever wires it up, this test should fail loudly and force
        // the caller to add real coverage.
        given().when().get("/api/cache/all")
                .then().statusCode(501);
    }
}
