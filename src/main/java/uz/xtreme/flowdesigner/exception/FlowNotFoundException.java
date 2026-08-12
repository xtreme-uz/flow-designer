package uz.xtreme.flowdesigner.exception;

/**
 * Exception thrown when a requested flow is not found.
 */
public class FlowNotFoundException extends RuntimeException {

    private final String flowName;
    private final String location;

    public FlowNotFoundException(String flowName, String location) {
        super("Flow not found: " + flowName + " in " + location);
        this.flowName = flowName;
        this.location = location;
    }

    public FlowNotFoundException(String flowName) {
        super("Flow not found: " + flowName);
        this.flowName = flowName;
        this.location = null;
    }

    public String getFlowName() {
        return flowName;
    }

    public String getLocation() {
        return location;
    }
}
