package uz.xtreme.flowdesigner.exception;

/**
 * Exception thrown when an optimistic locking conflict occurs.
 * The expected version does not match the current HEAD.
 */
public class GitVersionConflictException extends GitOperationException {

    private final String expectedVersion;
    private final String actualVersion;
    private final String workspaceId;

    public GitVersionConflictException(String expectedVersion, String actualVersion, String workspaceId) {
        super(String.format(
                "Version conflict in workspace '%s': expected version '%s' but found '%s'",
                workspaceId, expectedVersion, actualVersion));
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.workspaceId = workspaceId;
    }

    public String getExpectedVersion() {
        return expectedVersion;
    }

    public String getActualVersion() {
        return actualVersion;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }
}
