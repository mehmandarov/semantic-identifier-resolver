import cache.ArangoService;
import cache.LookupResultsCacheService;
import com.arangodb.entity.ArangoDBVersion;
import model.LookupQueueRequest;
import model.LookupRequestHttpPOST;
import utils.UUIDv5;

import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
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
        return "api-endpoints: Hai there!";
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
    @Produces(MediaType.APPLICATION_JSON)
    public ArangoDBVersion getVersion() {
        return arangoService.getVersion();
    }

    /*

    @GET
    @Path("/cache/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public LookupResult get(String key) {
        return new LookupResult(key, lookupCache.get(key));
    }


    @PUT
    @Path("/cache/{key}")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public void set(@PathParam("key") String key, String value) {
        lookupCache.set(key, value);
    }

    @GET
    @Path("/cache/all")
    @Produces(MediaType.APPLICATION_JSON)
    public Uni<List<String>> keys() {
        return lookupCache.keys();
    }

    */
}