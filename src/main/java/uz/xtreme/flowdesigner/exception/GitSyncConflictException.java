package uz.xtreme.flowdesigner.exception;

/**
 * Thrown when a Git sync operation is refused because the workspace and the
 * remote have diverged: a push rejected by the remote (non-fast-forward, hook)
 * or a pull that ended in a merge conflict.
 *
 * <p>These are not server faults — the user has to pull, resolve and retry —
 * so they map to HTTP 409 rather than 500.
 */
public class GitSyncConflictException extends RuntimeException {

    private final String operation;

    public GitSyncConflictException(String operation, String message) {
        super(message);
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }
}
