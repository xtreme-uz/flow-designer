package uz.xtreme.flowdesigner.service.git;

import java.util.List;

/**
 * State of a workspace relative to what has been committed and pushed.
 *
 * @param headCommit    current HEAD, or "empty" for a repository without commits
 * @param branchName    branch the workspace is on
 * @param changedFiles  paths under THUB/ with uncommitted changes — what a commit would stage
 * @param unmanagedFiles changed paths outside THUB/; a commit ignores them but a pull refuses to
 *                       run while they exist, so the client has to be able to show them
 * @param aheadCount    commits not yet pushed, 0 when the branch has no upstream
 * @param behindCount   commits on the remote that are not in the workspace
 * @param hasUpstream   whether the branch exists on the remote
 */
public record WorkspaceStatus(
        String headCommit,
        String branchName,
        List<String> changedFiles,
        List<String> unmanagedFiles,
        int aheadCount,
        int behindCount,
        boolean hasUpstream
) {
    public WorkspaceStatus {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        unmanagedFiles = unmanagedFiles == null ? List.of() : List.copyOf(unmanagedFiles);
    }

    public boolean clean() {
        return changedFiles.isEmpty();
    }
}
