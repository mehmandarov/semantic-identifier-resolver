
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Application;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import queue.QueueHandler;

import java.net.URISyntaxException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Path("/")
public class MyApplication {

    QueueHandler kanin;

    @Inject
    public MyApplication(QueueHandler kanin){
        this.kanin = kanin;
    }

    @Path("hi")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String entryPoint() throws URISyntaxException {
        return "Hai there!";
    }

    @Path("api/resolver")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Counted(name = "CUSTOM: Messaging service", absolute = true, tags={"purpose=total"})
    @Operation(summary = "CUSTOM: Main messaging service",
            description = "Main messaging service, may be slightly unstable at the moment. :-)")
    public Response idResolver() throws InterruptedException {
        ObjectNode jsonObject = kanin.postSimpleMessage("123", "reqCTX");
        return Response.ok(jsonObject.toString()).build();
    }

    @Path("api/retriever")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Counted(name = "CUSTOM: Messaging service", absolute = true, tags={"purpose=total"})
    @Operation(summary = "CUSTOM: Main messaging service",
            description = "Main messaging service, may be slightly unstable at the moment. :-)")
    public Response idRetriever() throws InterruptedException {
        ObjectNode jsonObject = kanin.getMessageFromExchange();

        // register queue
        // attach queue to an exchange
        // get all messages and relay

        return Response.ok(jsonObject.toString()).build();
    }
}