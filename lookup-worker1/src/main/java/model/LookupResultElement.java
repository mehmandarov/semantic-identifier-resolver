package model;

public class LookupResultElement {
    public String id;
    public String context;

    public LookupResultElement(String id, String context) {
        this.id = id;
        this.context = context;
    }

    public LookupResultElement() {
    }

    @Override
    public String toString() {
        return "LookupResultElement{" +
                "id='" + id + '\'' +
                ", context=" + context +
                '}';
    }
}
