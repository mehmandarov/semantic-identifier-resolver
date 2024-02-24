package model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;

@RegisterForReflection
public class LookupQueueRequest {

    @JsonProperty("id")
    public String id;
    @JsonProperty("context")
    public String context;
    @JsonProperty("requestID")
    public UUID requestID;
    @JsonProperty("requestHash")
    public UUID requestHash;

    ArrayList<LookupResultElement> results;

    /**
     * Default constructor required for Jackson serializer
     */
    public LookupQueueRequest() { }

    public LookupQueueRequest(String id, String context, String requestIDStr, String requestHashStr) {
        this.id = id;
        this.context = context;
        this.requestID = UUID.fromString(requestIDStr);
        this.requestHash = UUID.fromString(requestHashStr);
        this.results = new ArrayList<>();
    }

    @Override
    public String toString() {
        return "LookupRequest{" +
                "id='" + id + '\'' +
                ", context=" + context +
                ", requestID=" + requestID +
                ", requestHash=" + requestHash +
                ", results=" + results.stream().map(Object::toString).collect(Collectors.joining(", ")) +
                '}';
    }
}