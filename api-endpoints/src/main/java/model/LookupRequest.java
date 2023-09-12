package model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class LookupRequest {

    public String id;
    public String context;

    /**
     * Default constructor required for Jackson serializer
     */
    public LookupRequest() { }

    public LookupRequest(String id, String ctx) {
        this.id = id;
        this.context = ctx;
    }

    @Override
    public String toString() {
        return "Quote{" +
                "id='" + id + '\'' +
                ", context=" + context +
                '}';
    }
}