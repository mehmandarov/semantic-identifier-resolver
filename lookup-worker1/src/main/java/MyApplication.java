
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.rabbitmq.IncomingRabbitMQMessage;
import io.vertx.core.json.JsonObject;
import io.vertx.rabbitmq.impl.RabbitMQMessageImpl;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Request;
import model.LookupRequest;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.annotation.PreDestroy;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import queue.QueueHandler;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Random;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

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
        LookupRequest lookupReq = obj.mapTo(LookupRequest.class);
        // Change the response object to LookupRequest?
        String jsonObject = kanin.processLookupRequest(lookupReq);

        // register queue
        // attach queue to an exchange
        // get all messages and relay
        // {"id": 123, "context": "TAG"}
    }
}