package uz.xtreme.flowdesigner.exception;

/**
 * Exception thrown when flow storage operations fail (read/write JSON files).
 */
public class FlowStorageException extends RuntimeException {

    public FlowStorageException(String message) {
        super(message);
    }

    public FlowStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
