package uz.xtreme.flowdesigner.service.git;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Service interface for Git operations.
 * Manages a read-only main repository and per-user workspaces.
 */
public interface GitService {

    /**
     * Initializes the main repository by cloning from remote or opening existing.
     */
    void initMainRepo();

    /**
     * Pulls latest changes to the main repository.
     */
    void pullMainRepo();

    /**
     * Gets or creates a workspace for the given user and branch.
     * If the workspace doesn't exist, it will be created by cloning from remote.
     *
     * @param userId     the user identifier
     * @param branchName the branch name
     * @return the workspace info
     */
    WorkspaceInfo getOrCreateWorkspace(String userId, String branchName);

    /**
     * Gets an existing workspace without creating one.
     *
     * @param userId     the user identifier
     * @param branchName the branch name
     * @return the workspace info if it exists
     */
    Optional<WorkspaceInfo> getWorkspace(String userId, String branchName);

    /**
     * Pulls latest changes from remote to the workspace.
     *
     * @param workspace the workspace
     */
    void pull(WorkspaceInfo workspace);

    /**
     * Creates a new branch in the workspace.
     *
     * @param workspace  the workspace
     * @param branchName the new branch name
     */
    void createBranch(WorkspaceInfo workspace, String branchName);

    /**
     * Switches to a different branch in the workspace.
     *
     * @param workspace  the workspace
     * @param branchName the branch to checkout
     */
    void checkout(WorkspaceInfo workspace, String branchName);

    /**
     * Stages files for commit.
     *
     * @param workspace    the workspace
     * @param filePatterns file patterns to add (e.g., "." for all, "flows/*.json")
     */
    void add(WorkspaceInfo workspace, String... filePatterns);

    /**
     * Commits staged changes with optimistic locking and full audit trail.
     * Sets both author and committer to the user, and appends audit metadata as trailers.
     *
     * @param workspace       the workspace
     * @param message         the commit message
     * @param auditInfo       audit information (userId, name, email, IP, timestamp)
     * @param expectedVersion the expected HEAD commit hash (for optimistic locking), or null to skip check
     * @return the new commit hash
     */
    String commit(WorkspaceInfo workspace, String message, AuditInfo auditInfo, String expectedVersion);

    /**
     * Commits staged changes with optimistic locking (simple version without full audit).
     *
     * @param workspace       the workspace
     * @param message         the commit message
     * @param authorName      the author name
     * @param authorEmail     the author email
     * @param expectedVersion the expected HEAD commit hash (for optimistic locking), or null to skip check
     * @return the new commit hash
     * @deprecated Use {@link #commit(WorkspaceInfo, String, AuditInfo, String)} for full audit trail
     */
    @Deprecated
    String commit(WorkspaceInfo workspace, String message, String authorName, String authorEmail, String expectedVersion);

    /**
     * Pushes commits to remote.
     *
     * @param workspace the workspace
     */
    void push(WorkspaceInfo workspace);

    /**
     * Gets the current HEAD commit hash.
     *
     * @param workspace the workspace
     * @return the HEAD commit hash
     */
    String getHeadCommit(WorkspaceInfo workspace);

    /**
     * Removes a specific workspace.
     *
     * @param userId     the user identifier
     * @param branchName the branch name
     */
    void cleanupWorkspace(String userId, String branchName);

    /**
     * Cleans up idle workspaces that haven't been accessed recently.
     * Called periodically by scheduler.
     */
    void cleanupIdleWorkspaces();

    /**
     * Gets all active workspaces.
     *
     * @return collection of all workspace info
     */
    Collection<WorkspaceInfo> getAllWorkspaces();

    /**
     * Lists all remote branch names from the main repository.
     *
     * @return sorted list of branch names (without refs/remotes/origin/ prefix)
     */
    List<String> listBranches();
}
