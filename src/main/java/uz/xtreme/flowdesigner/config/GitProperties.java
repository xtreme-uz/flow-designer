package uz.xtreme.flowdesigner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for Git operations.
 */
@ConfigurationProperties(prefix = "app.git")
public record GitProperties(
        String remoteUrl,
        String mainRepoPath,
        String workspacesPath,
        String defaultBranch,
        Credentials credentials,
        Cleanup cleanup
) {
    public GitProperties {
        if (defaultBranch == null || defaultBranch.isBlank()) {
            defaultBranch = "main";
        }
        if (cleanup == null) {
            cleanup = new Cleanup(Duration.ofHours(24), Duration.ofMinutes(30), true);
        }
        if (credentials == null) {
            credentials = new Credentials(null, null, null);
        }
    }

    /**
     * Git credentials configuration.
     */
    public record Credentials(
            String username,
            String password,
            String sshKeyPath
    ) {
        public boolean isHttpAuth() {
            return username != null && !username.isBlank()
                    && password != null && !password.isBlank();
        }

        public boolean isSshAuth() {
            return sshKeyPath != null && !sshKeyPath.isBlank();
        }
    }

    /**
     * Workspace cleanup configuration.
     */
    public record Cleanup(
            Duration maxIdleTime,
            Duration checkInterval,
            boolean enabled
    ) {
        public Cleanup {
            if (maxIdleTime == null) {
                maxIdleTime = Duration.ofHours(24);
            }
            if (checkInterval == null) {
                checkInterval = Duration.ofMinutes(30);
            }
        }
    }
}
