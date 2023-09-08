
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
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

@ApplicationScoped
@Path("/")
public class MyApplication implements Runnable{

    QueueHandler kanin;
    private volatile boolean stopped = false;


    @Inject
    public MyApplication(QueueHandler kanin){
        this.kanin = kanin;
    }

    @Path("api/ping")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String entryPoint() throws URISyntaxException {
        kanin.getMessageFromExchange();
        return "lookup-worker1: Hai there!";
    }

    @Path("api/retriever")
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Counted(name = "CUSTOM: Messaging service", absolute = true, tags={"purpose=total"})
    @Operation(summary = "CUSTOM: Main messaging service",
            description = "Main messaging service, may be slightly unstable at the moment. :-)")
    public Response idRetriever() throws InterruptedException {
        String jsonObject = kanin.getMessageFromExchange();

        // register queue
        // attach queue to an exchange
        // get all messages and relay

        return Response.ok(jsonObject).build();
    }

    public void start(@Observes StartupEvent ev) {
        new Thread(this).start();
    }

    public void stop(@Observes ShutdownEvent ev) {
        stopped = true;
    }

    @Override
    public void run() {
        System.out.println("***** ======> Magic happens here!!");
        kanin.getMessageFromExchange();
    }
}