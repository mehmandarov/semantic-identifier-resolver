package model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class LookupRequestHttpPOST {

    @JsonProperty("id")
    public String id;
    @JsonProperty("context")
    public String context;

    /**
     * Default constructor required for Jackson serializer
     */
    public LookupRequestHttpPOST() { }

    public LookupRequestHttpPOST(String id, String context) {
        this.id = id;
        this.context = context;
    }

    @Override
    public String toString() {
        return "LookupRequestHttpPOST{" +
                "id='" + id + '\'' +
                ", context=" + context +
                '}';
    }
}