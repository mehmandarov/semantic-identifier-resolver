package model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.UUID;

public class LookupResult {

    @JsonProperty("requestID")
    public UUID requestID;
    @JsonProperty("requestHash")
    public UUID requestHash;
    public ArrayList<LookupResultElement> lookupResult;

    LookupResult(){
        lookupResult = new ArrayList<LookupResultElement>();
    }

    void add(LookupResultElement element){
        lookupResult.add(element);
    }
}
