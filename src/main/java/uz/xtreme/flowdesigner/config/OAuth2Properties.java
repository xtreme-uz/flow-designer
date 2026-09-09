package uz.xtreme.flowdesigner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Which Git host people sign in through, and the OAuth2 application registered
 * with it.
 *
 * <p>The choice is the administrator's: one instance signs in through GitLab,
 * another through GitHub, and the same build serves both. Only the selected
 * provider is registered, so nothing half-configured can be reached — and the
 * selection is what decides how a user's own token is presented to Git when
 * {@code app.git.use-user-credentials} is on.
 *
 * <p>Pick the provider that hosts {@code app.git.remote-url}. Signing in through
 * one host cannot authorise a push to another, so a GitHub flows repository with
 * GitLab login can only push with the service account.
 */
@ConfigurationProperties(prefix = "app.oauth2")
public record OAuth2Properties(
        Provider provider,
        Client gitlab,
        Client github
) {
    public OAuth2Properties {
        provider = provider == null ? Provider.GITLAB : provider;
        gitlab = gitlab == null ? Client.empty() : gitlab;
        github = github == null ? Client.empty() : github;
    }

    /** The Git hosts login can go through. */
    public enum Provider {
        GITLAB("GitLab"),
        GITHUB("GitHub");

        private final String displayName;

        Provider(String displayName) {
            this.displayName = displayName;
        }

        /** How the provider is written on the sign-in button. */
        public String displayName() {
            return displayName;
        }

        /** The Spring Security registration id, and the last path segment of the login URL. */
        public String registrationId() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * @param clientId     the OAuth2 application id issued by the provider
     * @param clientSecret its secret
     * @param baseUrl      the host people are redirected to — a self-hosted GitLab or
     *                     GitHub Enterprise Server instead of the public one
     * @param apiUrl       where user info is read from; GitHub serves it from a
     *                     separate host, GitLab from the same one
     * @param redirectUri  overrides the callback URL when the browser reaches the
     *                     application under a different address than the server sees
     * @param scope        what the token may do. Pushing as the signed-in user needs a
     *                     scope that grants repository write access
     */
    public record Client(
            String clientId,
            String clientSecret,
            String baseUrl,
            String apiUrl,
            String redirectUri,
            String scope
    ) {
        public static Client empty() {
            return new Client(null, null, null, null, null, null);
        }

        public boolean isConfigured() {
            return isSet(clientId) && isSet(clientSecret);
        }

        private static boolean isSet(String value) {
            return value != null && !value.isBlank();
        }
    }

    /** The settings of the selected provider. */
    public Client selectedClient() {
        return provider == Provider.GITHUB ? github : gitlab;
    }
}
