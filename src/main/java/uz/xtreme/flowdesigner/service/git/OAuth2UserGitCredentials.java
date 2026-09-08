package uz.xtreme.flowdesigner.service.git;

import uz.xtreme.flowdesigner.config.GitProperties;
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
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Uses the access token from the user's OAuth2 login as the Git password, so
 * pushes reach the remote as that user rather than as the service account.
 *
 * <p>Off unless {@code app.git.use-user-credentials} is set, because it only
 * works when the OAuth2 registration asks for a scope that grants repository
 * write access (on GitLab: {@code write_repository}). With the default
 * {@code read_user} scope the token cannot push, so enabling this without
 * widening the scope would break every push.
 */
@Component
public class OAuth2UserGitCredentials implements UserGitCredentials {

    private static final Logger log = LoggerFactory.getLogger(OAuth2UserGitCredentials.class);

    /** GitLab and GitHub both accept an OAuth2 token as the password under a fixed username. */
    private static final String OAUTH_USERNAME = "oauth2";

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

        return Optional.of(new UsernamePasswordCredentialsProvider(
                OAUTH_USERNAME, client.getAccessToken().getTokenValue()));
    }
}
