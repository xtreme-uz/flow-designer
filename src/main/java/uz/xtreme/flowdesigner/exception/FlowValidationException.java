package uz.xtreme.flowdesigner.exception;

import java.util.List;

/**
 * Exception thrown when flow validation fails.
 */
public class FlowValidationException extends RuntimeException {

    private final List<String> errors;

    public FlowValidationException(String message) {
        super(message);
        this.errors = List.of(message);
    }

    public FlowValidationException(List<String> errors) {
        super("Flow validation failed: " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
