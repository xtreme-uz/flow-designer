package uz.xtreme.flowdesigner.service.git;

import org.eclipse.jgit.transport.CredentialsProvider;

import java.util.Optional;

/**
 * Supplies the Git credentials of the user behind the current request.
 *
 * <p>Without this, every push carries the application's own service token: the
 * commit says who wrote it, but the remote sees one account, so the host's
 * branch protections and per-user permissions never come into play.
 */
@FunctionalInterface
public interface UserGitCredentials {

    /**
     * @return the current user's credentials, or empty when there is no user, no
     *         usable token, or the feature is switched off — the caller then falls
     *         back to the configured service credentials
     */
    Optional<CredentialsProvider> forCurrentUser();

    /** Always falls back to the service credentials. */
    static UserGitCredentials disabled() {
        return Optional::empty;
    }
}
