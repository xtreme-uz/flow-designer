package uz.xtreme.flowdesigner.exception;

/**
 * Base exception for Git operation failures.
 */
public class GitOperationException extends RuntimeException {

    public GitOperationException(String message) {
        super(message);
    }

    public GitOperationException(String message, Throwable cause) {
        super(message, cause);
    }
}
