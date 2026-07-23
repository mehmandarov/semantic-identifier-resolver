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


    /**
     * The processing step of the worker: the plug-in slot where the resolution
     * logic goes. The logic is use-case specific and has to be implemented
     * separately for each worker, encapsulating one source or one set of rules
     * (e.g. semantic lifting of tag numbers, or a key-value mapping for one
     * system). Version 1 logs the request and returns it unchanged; it does
     * not write results back to the cache yet.
     */
    public String processLookupRequest(LookupQueueRequest lookupReq) {
        System.out.println("*************Looked up ID: "+ lookupReq.id + " and context: " + lookupReq.context);
        return lookupReq.toString();
    }
}
