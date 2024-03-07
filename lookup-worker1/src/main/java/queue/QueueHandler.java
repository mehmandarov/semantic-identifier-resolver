package queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.*;
import jakarta.enterprise.context.ApplicationScoped;
import model.LookupQueueRequest;
import utils.UUIDv5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

@ApplicationScoped
public class QueueHandler {


    public String processLookupRequest(LookupQueueRequest lookupReq) {
        System.out.println("*************Looked up ID: "+ lookupReq.id + " and context: " + lookupReq.context);
        return lookupReq.toString();
    }
}
