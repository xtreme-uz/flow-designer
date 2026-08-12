package uz.xtreme.flowdesigner.exception;

/**
 * Exception thrown when a requested workspace does not exist.
 */
public class WorkspaceNotFoundException extends GitOperationException {

    private final String workspaceId;

    public WorkspaceNotFoundException(String workspaceId) {
        super(String.format("Workspace not found: '%s'", workspaceId));
        this.workspaceId = workspaceId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }
}
