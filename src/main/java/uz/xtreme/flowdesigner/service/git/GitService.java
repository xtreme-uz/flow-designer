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
     * Pulls latest changes to the main repository, at most once every 30 seconds.
     * Reads of the main branch call this freely; the throttle keeps opening a
     * flow from turning into several round-trips to the remote.
     */
    void pullMainRepo();

    /**
     * @param force bypass the throttle, for callers that must see the latest
     *              state — the refresh after a push, or the scheduled refresh
     */
    void pullMainRepo(boolean force);

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
     * Creates a branch at the workspace's current HEAD and publishes it, without
     * moving this workspace off its own branch. Publishing matters: a workspace
     * for the new branch is a fresh clone, so an unpublished branch would start
     * from the default branch instead of the work it was branched from.
     *
     * @param workspace  the workspace to branch from
     * @param branchName the new branch name
     */
    void createAndPushBranch(WorkspaceInfo workspace, String branchName);

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
     * Stages everything this application writes — the THUB data files and the
     * canvas layouts beside them — and nothing else in the clone.
     *
     * @param workspace the workspace
     */
    void addManagedFiles(WorkspaceInfo workspace);

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
     * Reports what is uncommitted and unpushed in the workspace.
     *
     * @param workspace the workspace
     * @return the workspace status
     */
    WorkspaceStatus getStatus(WorkspaceInfo workspace);

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
     * Runs an action holding the workspace lock, so that a multi-step operation
     * (read files, write files, stage, commit) cannot interleave with another
     * request on the same workspace. The lock is reentrant: nested calls into
     * this service from within the action are safe.
     *
     * @param workspace the workspace to lock
     * @param action    the work to run under the lock
     */
    void withWorkspaceLock(WorkspaceInfo workspace, Runnable action);

    /**
     * Value-returning variant of {@link #withWorkspaceLock(WorkspaceInfo, Runnable)}.
     */
    <T> T withWorkspaceLock(WorkspaceInfo workspace, java.util.function.Supplier<T> action);

    /**
     * Lists all remote branch names from the main repository.
     *
     * @return sorted list of branch names (without refs/remotes/origin/ prefix)
     */
    List<String> listBranches();
}
