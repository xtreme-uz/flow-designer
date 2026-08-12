package uz.xtreme.flowdesigner.service.git;

import java.time.Instant;

/**
 * Audit information for Git commits.
 * Contains user identity information for tracking who made changes.
 */
public record AuditInfo(
        String userId,
        String userName,
        String userEmail,
        String ipAddress,
        Instant timestamp
) {
    /**
     * Creates AuditInfo with current timestamp.
     */
    public static AuditInfo of(String userId, String userName, String userEmail) {
        return new AuditInfo(userId, userName, userEmail, null, Instant.now());
    }

    /**
     * Creates AuditInfo with IP address and current timestamp.
     */
    public static AuditInfo of(String userId, String userName, String userEmail, String ipAddress) {
        return new AuditInfo(userId, userName, userEmail, ipAddress, Instant.now());
    }

    /**
     * Formats audit metadata as Git commit message trailers.
     */
    public String toTrailers() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n");
        sb.append("User-Id: ").append(userId).append("\n");
        if (ipAddress != null && !ipAddress.isBlank()) {
            sb.append("Client-IP: ").append(ipAddress).append("\n");
        }
        sb.append("Timestamp: ").append(timestamp).append("\n");
        return sb.toString();
    }
}
