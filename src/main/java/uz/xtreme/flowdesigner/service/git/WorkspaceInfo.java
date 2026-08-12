package uz.xtreme.flowdesigner.service.git;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Metadata about a user's Git workspace.
 */
public record WorkspaceInfo(
        String id,
        String userId,
        String branchName,
        Path path,
        Instant createdAt,
        Instant lastAccessedAt
) {
    /**
     * Creates a workspace ID from userId and branchName.
     * Branch names are sanitized to be filesystem-safe.
     */
    public static String createId(String userId, String branchName) {
        String sanitizedBranch = branchName.replace("/", "_");
        return userId + "-" + sanitizedBranch;
    }

    /**
     * Creates a new WorkspaceInfo with updated lastAccessedAt timestamp.
     */
    public WorkspaceInfo withLastAccessedAt(Instant timestamp) {
        return new WorkspaceInfo(id, userId, branchName, path, createdAt, timestamp);
    }
}
