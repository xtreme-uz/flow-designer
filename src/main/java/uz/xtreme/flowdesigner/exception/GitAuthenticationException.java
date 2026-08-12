package uz.xtreme.flowdesigner.exception;

/**
 * Exception thrown when Git authentication fails.
 */
public class GitAuthenticationException extends GitOperationException {

    public GitAuthenticationException(String message) {
        super(message);
    }

    public GitAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
