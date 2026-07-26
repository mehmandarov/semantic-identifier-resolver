import cache.ArangoService;
import com.arangodb.entity.ArangoDBVersion;
import com.arangodb.entity.BaseDocument;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import model.LookupQueueRequest;
import model.LookupRequestHttpPOST;
import utils.UUIDv5;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import io.micrometer.core.annotation.Counted;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Path("/api")
public class MyApplication {

    @Inject
    ArangoService arangoService;

    @Channel("idMapper")
    Emitter<LookupQueueRequest> lookupRequesttEmitter;

    @Path("ping")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String entryPoint() {
        return "api-endpoints: Hai there! PONG.";
    }

    @Path("lookup")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Counted(value = "id_lookup_service", extraTags = {"purpose", "ID_lookup"})
    @Operation(summary = "CUSTOM: Main lookup service",
            description = "Main lookup service, with a key and a context provided. No filtering. " +
                    "Accepts the lookup for asynchronous processing: answers 202 with the enriched " +
                    "request as a receipt, and a Location header pointing at the results endpoint.")
    @APIResponse(responseCode = "202", description = "Request accepted for processing; the Location header points at /api/cache/{requestHash}")
    @APIResponse(responseCode = "400", description = "Missing or blank id or context")
    public Response idResolver(LookupRequestHttpPOST request, @Context UriInfo uriInfo) {
        if (request == null || isBlank(request.id) || isBlank(request.context)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Both 'id' and 'context' must be provided and non-blank."))
                    .build();
        }
        UUID correlationID = UUID.randomUUID();
        UUID requestHash = UUIDv5.fromUTF8(request.id.toUpperCase()+"_"+request.context.toUpperCase());
        LookupQueueRequest lookupQueueRequest = new LookupQueueRequest(request.id, request.context, correlationID.toString(), requestHash.toString());

        // Record the request as pending before publishing, so the results URL
        // answers polls from the moment the receipt is issued.
        arangoService.recordPendingRequest(lookupQueueRequest);

        System.out.println("*** SENDING A LOOKUP REQUEST ***");
        lookupRequesttEmitter.send(lookupQueueRequest);

        URI resultsUrl = uriInfo.getBaseUriBuilder()
                .path("api").path("cache").path(requestHash.toString())
                .build();
        return Response.accepted(lookupQueueRequest).location(resultsUrl).build();
    }

    @GET
    @Path("/internals/arangodb")
    @Produces(MediaType.APPLICATION_JSON)
    public ArangoDBVersion getVersion() {
        return arangoService.getVersion();
    }


    @GET
    @Path("/cache/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "CUSTOM: Results endpoint",
            description = "Reports the state of a lookup request by its request hash. " +
                    "The response code reflects the outcome: 200 done, 202 pending or " +
                    "in progress (claimedBy lists the workers that picked the request up), " +
                    "404 unknown request hash, 500 worker-reported failure, 504 timed out.")
    @APIResponse(responseCode = "200", description = "Lookup done; the body carries the results")
    @APIResponse(responseCode = "202", description = "Lookup accepted and pending, or claimed by a worker and in progress")
    @APIResponse(responseCode = "400", description = "Blank request hash")
    @APIResponse(responseCode = "404", description = "Unknown request hash")
    @APIResponse(responseCode = "500", description = "Lookup failed on the server side; the body carries the detail")
    @APIResponse(responseCode = "504", description = "Lookup timed out before any worker completed it")
    public Response get(@PathParam("key") String key) {
        if (isBlank(key)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "The request hash must be provided."))
                    .build();
        }
        BaseDocument doc = arangoService.getRequestDocument(key.trim());
        if (doc == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "No lookup request found for request hash: " + key))
                    .build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestID", doc.getAttribute("requestID"));
        body.put("requestHash", doc.getKey());
        body.put("id", doc.getAttribute("id"));
        body.put("context", doc.getAttribute("context"));
        String status = String.valueOf(doc.getAttribute("status"));
        body.put("status", status);
        // Pick-up history: which worker(s) claimed the request, and when.
        Object claimedBy = doc.getAttribute("claimedBy");
        if (claimedBy != null) {
            body.put("claimedBy", claimedBy);
        }
        switch (status) {
            case ArangoService.STATUS_DONE:
                body.put("results", doc.getAttribute("results"));
                // Provenance: which worker answered, and when.
                body.put("resolvedBy", doc.getAttribute("resolvedBy"));
                body.put("resolvedAt", doc.getAttribute("resolvedAt"));
                return Response.ok(body).build();
            case ArangoService.STATUS_PENDING:
            case ArangoService.STATUS_IN_PROGRESS:
                return Response.accepted(body).build();
            case ArangoService.STATUS_TIMED_OUT:
                body.put("detail", doc.getAttribute("detail"));
                return Response.status(Response.Status.GATEWAY_TIMEOUT).entity(body).build();
            case ArangoService.STATUS_ERROR:
            default:
                body.put("detail", doc.getAttribute("detail"));
                body.put("resolvedBy", doc.getAttribute("resolvedBy"));
                body.put("resolvedAt", doc.getAttribute("resolvedAt"));
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR).entity(body).build();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @GET
    @Path("/cache/all")
    @Produces(MediaType.APPLICATION_JSON)
    public Response keys() {
        return Response.status(Response.Status.NOT_IMPLEMENTED).entity("Not implemented yet.").build();

    }

}