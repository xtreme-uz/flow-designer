package uz.xtreme.flowdesigner.service.git;

import uz.xtreme.flowdesigner.config.GitProperties;
import uz.xtreme.flowdesigner.config.OAuth2Properties;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Uses the access token from the user's OAuth2 login as the Git password, so
 * pushes reach the remote as that user rather than as the service account.
 *
 * <p>Off unless {@code app.git.use-user-credentials} is set, because it only
 * works when the OAuth2 registration asks for a scope that grants repository
 * write access (GitLab: {@code write_repository}, GitHub: {@code repo}). With
 * the default read-only scope the token cannot push, so enabling this without
 * widening the scope would break every push.
 *
 * <p>It also only works when the login provider hosts the flows repository: a
 * GitLab token is not accepted by GitHub, and the other way round.
 */
@Component
public class OAuth2UserGitCredentials implements UserGitCredentials {

    private static final Logger log = LoggerFactory.getLogger(OAuth2UserGitCredentials.class);

    /**
     * The username an OAuth2 token travels under in HTTP basic auth. Both hosts
     * take the token as the password, but each expects its own fixed username —
     * GitHub rejects GitLab's "oauth2".
     */
    private static final String GITLAB_OAUTH_USERNAME = "oauth2";
    private static final String GITHUB_OAUTH_USERNAME = "x-access-token";
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    private final GitProperties gitProperties;
    private final ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService;

    public OAuth2UserGitCredentials(GitProperties gitProperties,
                                    ObjectProvider<OAuth2AuthorizedClientService> authorizedClientService) {
        this.gitProperties = gitProperties;
        this.authorizedClientService = authorizedClientService;
        if (gitProperties.useUserCredentials()) {
            log.info("Git operations will use each user's own OAuth2 token where one is available");
        }
    }

    /** Treats a token about to expire as expired: the push may outlive the margin. */
    private static boolean isExpired(OAuth2AccessToken accessToken) {
        Instant expiresAt = accessToken.getExpiresAt();
        return expiresAt != null && expiresAt.isBefore(Instant.now().plus(EXPIRY_MARGIN));
    }

    @Override
    public Optional<CredentialsProvider> forCurrentUser() {
        if (!gitProperties.useUserCredentials()) {
            return Optional.empty();
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            // Scheduled work and unauthenticated calls have no user
            return Optional.empty();
        }

        OAuth2AuthorizedClientService service = authorizedClientService.getIfAvailable();
        if (service == null) {
            return Optional.empty();
        }

        OAuth2AuthorizedClient client =
                service.loadAuthorizedClient(token.getAuthorizedClientRegistrationId(), token.getName());
        if (client == null || client.getAccessToken() == null) {
            log.debug("No stored OAuth2 token for '{}', falling back to the service credentials", token.getName());
            return Optional.empty();
        }

        if (isExpired(client.getAccessToken())) {
            // Falling back keeps the operation working on the service account;
            // handing over a dead token would fail it outright
            log.debug("OAuth2 token for '{}' has expired, falling back to the service credentials",
                    token.getName());
            return Optional.empty();
        }

        return Optional.of(new UsernamePasswordCredentialsProvider(
                oauthUsername(token.getAuthorizedClientRegistrationId()),
                client.getAccessToken().getTokenValue()));
    }

    private static String oauthUsername(String registrationId) {
        return OAuth2Properties.Provider.GITHUB.registrationId().equals(registrationId)
                ? GITHUB_OAUTH_USERNAME
                : GITLAB_OAUTH_USERNAME;
    }
}
