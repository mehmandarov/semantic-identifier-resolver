package model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class LookupRequest {

    @JsonProperty("id")
    public String id;
    @JsonProperty("context")
    public String context;

    /**
     * Default constructor required for Jackson serializer
     */
    public LookupRequest() { }

    public LookupRequest(String id, String context) {
        this.id = id;
        this.context = context;
    }

    @Override
    public String toString() {
        return "LookupRequest{" +
                "id='" + id + '\'' +
                ", context=" + context +
                '}';
    }
}