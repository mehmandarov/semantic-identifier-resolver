package queue;

import jakarta.enterprise.context.ApplicationScoped;
import model.LookupQueueRequest;
import model.LookupResultElement;

import java.util.List;

@ApplicationScoped
public class QueueHandler {


    /**
     * The processing step of the worker: the plug-in slot where the resolution
     * logic goes. The logic is use-case specific and has to be implemented
     * separately for each worker, encapsulating one or several sources or
     * sets of rules (e.g. semantic lifting of tag numbers, or a key-value
     * mapping for one system). Version 1 implements the simplest case from
     * Paper I: with no plug-in logic available, the system returns what it
     * was told, so the result echoes the input tuple.
     */
    public List<LookupResultElement> processLookupRequest(LookupQueueRequest lookupReq) {
        System.out.println("*************Looked up ID: "+ lookupReq.id + " and context: " + lookupReq.context);
        // Echo case: the result is the input tuple itself, so the relationship
        // is identity ("same-as"). Real plug-ins supply relations such as
        // "part-of" / "has-part" between the input and the returned identifier.
        return List.of(new LookupResultElement(lookupReq.id, lookupReq.context, "same-as"));
    }
}
