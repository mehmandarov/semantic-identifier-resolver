import cache.ArangoService;
import cache.LookupResultsCacheService;
import com.arangodb.entity.ArangoDBVersion;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import model.LookupQueueRequest;
import model.LookupRequestHttpPOST;
import model.LookupResult;
import utils.UUIDv5;

import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.net.URISyntaxException;
import java.util.UUID;

@Path("/api")
public class MyApplication {

    @Inject
    LookupResultsCacheService lookupCache;

    @Inject
    ArangoService arangoService;

    @Channel("idMapper")
    Emitter<LookupQueueRequest> lookupRequesttEmitter;

    @Path("ping")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String entryPoint() throws URISyntaxException {
        return "api-endpoints: Hai there! PONG.";
    }

    @Path("lookup")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Counted(name = "ID lookup service", absolute = true, tags={"purpose=//TODO"})
    @Operation(summary = "CUSTOM: Main messaging service",
            description = "Main messaging service, may be slightly unstable at the moment. :-)")
    public LookupQueueRequest idResolver(LookupRequestHttpPOST request) throws InterruptedException {
        UUID correlationID = UUID.randomUUID();
        UUID requestHash = UUIDv5.fromUTF8(request.id.toUpperCase()+"_"+request.context.toUpperCase());
        LookupQueueRequest lookupQueueRequest = new LookupQueueRequest(request.id, request.context, correlationID.toString(), requestHash.toString());
        lookupRequesttEmitter.send(lookupQueueRequest);
        return lookupQueueRequest;
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
    public Response get(String key) {
        /*
        if(uuid == null || uuid.trim().length() == 0) {
            return Response.serverError().entity("UUID cannot be blank").build();
        }
        Entity entity = service.getById(uuid);
        if(entity == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Entity not found for UUID: " + uuid).build();
        }
        String json = //convert entity to json
        return Response.ok(json, MediaType.APPLICATION_JSON).build();
         */
        return Response.status(Response.Status.NOT_IMPLEMENTED).entity("Not implemented yet.").build();
    }

    /*
    @PUT
    @Path("/cache/{key}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public void set(@PathParam("key") String key, String value) {
        //set HTTP code to "201 Created"
        lookupCache.set(key, value);
    }
    */

    @GET
    @Path("/cache/all")
    @Produces(MediaType.APPLICATION_JSON)
    public Response keys() {
        return Response.status(Response.Status.NOT_IMPLEMENTED).entity("Not implemented yet.").build();

    }

}