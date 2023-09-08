package queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.*;
import jakarta.enterprise.context.ApplicationScoped;
import utils.UUIDv5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

@ApplicationScoped
public class QueueHandler {


    public ObjectNode postSimpleMessage(String id, String ctx){
        String exchangeName = "exchange_id_mapper";
        String routingKey = "routing_id_lookup_with_ctx";
        String queueName = "queue_tag_lookup";
        ObjectNode json = null;

        ConnectionFactory factory = new ConnectionFactory();
        // "guest"/"guest" by default, limited to localhost connections
        factory.setUsername("guest");
        factory.setPassword("guest");
        //factory.setVirtualHost(virtualHost);
        factory.setHost("localhost");
        factory.setPort(5672);

        try ( Connection conn = factory.newConnection();
              Channel channel = conn.createChannel(); ) {

            conn.addShutdownListener(new ShutdownListener() {
                public void shutdownCompleted(ShutdownSignalException cause)
                {
                    if (cause.isHardError())
                    {
                        Connection conn = (Connection)cause.getReference();
                        if (!cause.isInitiatedByApplication())
                        {
                            Method reason = cause.getReason();
                            System.out.println("**************NOT InitiatedByApplication:");
                            System.out.println(reason.toString());
                            //...
                        }
                        System.out.println("************** HARD ERROR - InitiatedByApplication");
                        System.out.println(cause.getMessage());
                        //System.out.println(conn.getCloseReason());
                        //...
                    } else {
                        Channel ch = (Channel)cause.getReference();
                        System.out.println("**************NOT HardError:");
                        System.out.println(cause.getMessage());
                        //...
                    }
                }
            });

            // Generate a UUID v4
            UUID correlationID = UUID.randomUUID();

            // Create an ObjectMapper instance
            json = getJSON(correlationID, id, ctx);

            channel.exchangeDeclare(exchangeName, "direct", true);
            String message = json.toString();
            channel.basicPublish(exchangeName, routingKey, null, message.getBytes(StandardCharsets.UTF_8));


        } catch (IOException | TimeoutException e) {
            throw new RuntimeException(e);
        }
        return json;
    }

    private static ObjectNode getJSON(UUID correlationID, String id, String ctx) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();

        // Create an empty JSON object
        ObjectNode jsonObject = objectMapper.createObjectNode();

        // Add key-value pairs to the JSON object
        jsonObject.put("correlationID", correlationID.toString());

        // Add another object to be nested
        ObjectNode requestObject = objectMapper.createObjectNode();
        requestObject.put("id", id);
        requestObject.put("ctx", ctx);
        requestObject.put("requestHash", UUIDv5.fromUTF8(id.toUpperCase()+"_"+ctx.toUpperCase()).toString());
        jsonObject.set("request", requestObject);

        return jsonObject;
    }

    public String getMessageFromExchange() {
        String EXCHANGE_NAME = "exchange_id_mapper";
        String QUEUE_NAME = "my_TESTAPP_queue_app1";
        String ROUTING_KEY = "routing_id_lookup_with_ctx";

        ConnectionFactory factory = new ConnectionFactory();

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            // Declare the exchange (if not already declared)
            //channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.DIRECT);

            // Declare the queue (if not already declared)
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);

            // Bind the queue to the exchange with the routing key
            channel.queueBind(QUEUE_NAME, EXCHANGE_NAME, ROUTING_KEY);

            System.out.println(" [*] Waiting for messages. To exit, press Ctrl+C");

            // Create a consumer and set up a callback to handle incoming messages
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                System.out.println(" [x] Received '" + message + "'");
            };

            // Start consuming messages from the queue
            channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> {
            });
        } catch (IOException | TimeoutException e) {
        throw new RuntimeException(e);
    }
        return null;
    }
}
