package uz.xtreme.flowdesigner.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OAuth2 provider selection")
class ClientRegistrationConfigTest {

    private final ClientRegistrationConfig config = new ClientRegistrationConfig();

    private static OAuth2Properties.Client client(String baseUrl, String scope) {
        return new OAuth2Properties.Client("id", "secret", baseUrl, null, null, scope);
    }

    private ClientRegistration registrationFor(OAuth2Properties properties) {
        ClientRegistrationRepository repository = config.clientRegistrationRepository(properties);
        return repository.findByRegistrationId(properties.provider().registrationId());
    }

    @Test
    @DisplayName("Registers GitLab when the administrator selects it")
    void gitlabSelected() {
        ClientRegistration registration = registrationFor(new OAuth2Properties(
                OAuth2Properties.Provider.GITLAB, client(null, null), OAuth2Properties.Client.empty()));

        assertEquals("gitlab", registration.getRegistrationId());
        assertEquals("https://gitlab.com/oauth/authorize", registration.getProviderDetails().getAuthorizationUri());
        assertEquals("https://gitlab.com/api/v4/user",
                registration.getProviderDetails().getUserInfoEndpoint().getUri());
        assertEquals("username", registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
    }

    @Test
    @DisplayName("Registers GitHub when the administrator selects it")
    void githubSelected() {
        ClientRegistration registration = registrationFor(new OAuth2Properties(
                OAuth2Properties.Provider.GITHUB, OAuth2Properties.Client.empty(), client(null, null)));

        assertEquals("github", registration.getRegistrationId());
        assertEquals("https://github.com/login/oauth/authorize",
                registration.getProviderDetails().getAuthorizationUri());
        // GitHub serves the API from a host of its own
        assertEquals("https://api.github.com/user",
                registration.getProviderDetails().getUserInfoEndpoint().getUri());
        // 'id' is a number; 'login' is the account name people recognise
        assertEquals("login", registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
    }

    @Test
    @DisplayName("Registers only the selected provider")
    void otherProviderIsNotRegistered() {
        ClientRegistrationRepository repository = config.clientRegistrationRepository(new OAuth2Properties(
                OAuth2Properties.Provider.GITHUB, client(null, null), client(null, null)));

        assertNotNull(repository.findByRegistrationId("github"));
        // Both are configured here; a second registration would put a button on the
        // login page for a host this instance is not set up to push to
        assertNull(repository.findByRegistrationId("gitlab"));
    }

    @Test
    @DisplayName("Supports self-hosted instances")
    void selfHostedBaseUrls() {
        ClientRegistration gitlab = registrationFor(new OAuth2Properties(
                OAuth2Properties.Provider.GITLAB,
                client("https://gitlab.example.com/", null), OAuth2Properties.Client.empty()));
        // The trailing slash must not survive into the URLs
        assertEquals("https://gitlab.example.com/oauth/token", gitlab.getProviderDetails().getTokenUri());

        ClientRegistration github = registrationFor(new OAuth2Properties(
                OAuth2Properties.Provider.GITHUB,
                OAuth2Properties.Client.empty(), client("https://github.example.com", null)));
        // GitHub Enterprise Server serves its API from the same host
        assertEquals("https://github.example.com/api/v3/user",
                github.getProviderDetails().getUserInfoEndpoint().getUri());
    }

    @Test
    @DisplayName("Splits a configured scope list, spaces and all")
    void scopesAreSplit() {
        ClientRegistration registration = registrationFor(new OAuth2Properties(
                OAuth2Properties.Provider.GITHUB, OAuth2Properties.Client.empty(),
                client(null, "read:user, user:email , repo")));

        assertEquals(Set.of("read:user", "user:email", "repo"), registration.getScopes());
    }

    @Test
    @DisplayName("Refuses to start when the selected provider has no client id")
    void selectedProviderMustBeConfigured() {
        // Starting with a half-configured registration would fail at the provider,
        // long after the setting that is missing could be pointed at
        OAuth2Properties properties = new OAuth2Properties(
                OAuth2Properties.Provider.GITHUB, client(null, null), OAuth2Properties.Client.empty());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> config.clientRegistrationRepository(properties));
        assertTrue(failure.getMessage().contains("GITHUB_CLIENT_ID"), failure.getMessage());
    }

    @Test
    @DisplayName("Defaults to GitLab, as before the provider became a choice")
    void defaultsToGitlab() {
        OAuth2Properties properties = new OAuth2Properties(null, client(null, null), null);

        assertEquals(OAuth2Properties.Provider.GITLAB, properties.provider());
        assertEquals("gitlab", registrationFor(properties).getRegistrationId());
    }
}
