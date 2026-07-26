package model;

/**
 * One element of a lookup result, as specified by the Paper I interface:
 * an identifier, its context, and the relationship of the returned
 * identifier to the input identifier (e.g. "same-as", "part-of",
 * "has-part" — the interface is explicitly not limited to pure
 * equivalence mappings).
 */
public class LookupResultElement {
    public String id;
    public String context;
    public String relationship;

    public LookupResultElement(String id, String context, String relationship) {
        this.id = id;
        this.context = context;
        this.relationship = relationship;
    }

    public LookupResultElement() {
    }

    @Override
    public String toString() {
        return "LookupResultElement{" +
                "id='" + id + '\'' +
                ", context=" + context +
                ", relationship=" + relationship +
                '}';
    }
}
