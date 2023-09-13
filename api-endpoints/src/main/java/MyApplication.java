
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import model.LookupQueueRequest;
import model.LookupRequestHttpPOST;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import queue.QueueHandler;
import utils.UUIDv5;

import java.net.URISyntaxException;
import java.util.UUID;

@Path("/api")
public class MyApplication {

    QueueHandler kanin;

    @Inject
    public MyApplication(QueueHandler kanin){
        this.kanin = kanin;
    }

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
    @Counted(name = "CUSTOM: Messaging service", absolute = true, tags={"purpose=total"})
    @Operation(summary = "CUSTOM: Main messaging service",
            description = "Main messaging service, may be slightly unstable at the moment. :-)")
    public LookupQueueRequest idResolver(LookupRequestHttpPOST request) throws InterruptedException {
        UUID correlationID = UUID.randomUUID();
        UUID requestHash = UUIDv5.fromUTF8(request.id.toUpperCase()+"_"+request.context.toUpperCase());
        LookupQueueRequest lookupQueueRequest = new LookupQueueRequest(request.id, request.context, correlationID.toString(), requestHash.toString());
        lookupRequesttEmitter.send(lookupQueueRequest);
        return lookupQueueRequest;
    }
}