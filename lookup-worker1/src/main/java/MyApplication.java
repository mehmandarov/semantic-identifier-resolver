
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import queue.QueueHandler;

import java.net.URISyntaxException;

@ApplicationScoped
@Path("/")
public class MyApplication{

    QueueHandler kanin;


    @Inject
    public MyApplication(QueueHandler kanin){
        this.kanin = kanin;
    }

    @Path("api/ping")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String entryPoint() throws URISyntaxException {
        return "lookup-worker1: Hai there!";
    }

    @Incoming("idMapper")
    //@Outgoing("quotes")
    @Blocking
    @Counted(name = "CUSTOM: ID lookup service, app1", absolute = true, tags={"purpose=total"})
    @Operation(summary = "CUSTOM: ID lookup service, app1",
            description = "Lookup a certain ID")
    public void idRetriever(JsonObject obj) throws InterruptedException {
        LookupQueueRequest lookupReq = obj.mapTo(LookupQueueRequest.class);
        // Change the response object to LookupRequest?
        String jsonObject = kanin.processLookupRequest(lookupReq);

        // register queue
        // attach queue to an exchange
        // get all messages and relay
        // {"id": 123, "context": "TAG"}
    }
}