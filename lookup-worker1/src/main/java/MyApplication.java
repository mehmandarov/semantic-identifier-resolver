
import io.smallrye.common.annotation.Blocking;
import io.smallrye.reactive.messaging.rabbitmq.OutgoingRabbitMQMetadata;
import io.vertx.core.json.JsonObject;
import io.micrometer.core.annotation.Counted;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import model.LookupQueueRequest;
import org.eclipse.microprofile.openapi.annotations.Operation;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.reactive.messaging.*;
import queue.QueueHandler;

import java.net.URISyntaxException;
import java.time.ZonedDateTime;

@ApplicationScoped
@Path("/")
public class MyApplication{

    @Inject
    @Channel("idMapperReplyAck")
    Emitter<String> emitterForAck;

    @Inject
    QueueHandler queueHandler;

    @Path("api/ping")
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String entryPoint() throws URISyntaxException {
        return "lookup-worker1: Hai there!";
    }

    @Incoming("idMapper")
    //@Outgoing("idMapperReplyAck")
    @Blocking
    @Counted(value = "id_lookup_service_app1", extraTags = {"purpose", "total"})
    @Operation(summary = "CUSTOM: ID lookup service, app1",
            description = "Lookup a certain ID")
    public void idRetriever(JsonObject obj) throws InterruptedException {
        LookupQueueRequest lookupReq = obj.mapTo(LookupQueueRequest.class);
        // Processing step: the plug-in slot for the use-case specific
        // resolution logic of this worker (see QueueHandler).
        queueHandler.processLookupRequest(lookupReq);

        final OutgoingRabbitMQMetadata metadata = new OutgoingRabbitMQMetadata.Builder()
                .withHeader("APP_NAME", "lookup-worker-1")
                .withRoutingKey("id_lookup_akk")
                .withTimestamp(ZonedDateTime.now())
                .build();
        System.out.println("*** SENDING AN ACK ***");
        emitterForAck.send(Message.of("ACK", Metadata.of(metadata)));
    }
}