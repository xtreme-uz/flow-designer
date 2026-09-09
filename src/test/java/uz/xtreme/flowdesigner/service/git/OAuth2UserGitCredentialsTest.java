package uz.xtreme.flowdesigner.service.git;

import uz.xtreme.flowdesigner.config.GitProperties;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Per-user Git credentials")
class OAuth2UserGitCredentialsTest {

    private static final String REGISTRATION_ID = "gitlab";
    private static final String USERNAME = "alisher";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private GitProperties properties(boolean useUserCredentials) {
        return new GitProperties("https://example.test/repo.git", "/tmp/main", "/tmp/workspaces",
                "main", useUserCredentials, new GitProperties.Credentials(null, null, null), null);
    }

    private OAuth2AuthorizedClientService serviceWithToken(String token) {
        return serviceWithToken(token, Instant.now().plusSeconds(3600));
    }

    private OAuth2AuthorizedClientService serviceWithToken(String token, Instant expiresAt) {
        return serviceWithToken(REGISTRATION_ID, token, expiresAt);
    }

    private OAuth2AuthorizedClientService serviceWithToken(String registrationId, String token, Instant expiresAt) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(registrationId)
                .clientId("id")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://example.test/oauth/authorize")
                .tokenUri("https://example.test/oauth/token")
                .userInfoUri("https://example.test/api/v4/user")
                .userNameAttributeName("username")
                .build();
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(registration, USERNAME,
                new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, token,
                        Instant.now().minusSeconds(60), expiresAt));

        OAuth2AuthorizedClientService service = mock(OAuth2AuthorizedClientService.class);
        when(service.loadAuthorizedClient(registrationId, USERNAME)).thenReturn(client);
        return service;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<OAuth2AuthorizedClientService> provider(OAuth2AuthorizedClientService service) {
        ObjectProvider<OAuth2AuthorizedClientService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }

    private void authenticateOAuth2User() {
        authenticateOAuth2User(REGISTRATION_ID);
    }

    private void authenticateOAuth2User(String registrationId) {
        var principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("username", USERNAME), "username");
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), registrationId));
    }

    @Test
    @DisplayName("Uses the signed-in user's token as the Git password")
    void usesUserToken() throws URISyntaxException {
        authenticateOAuth2User();
        var credentials = new OAuth2UserGitCredentials(properties(true), provider(serviceWithToken("glpat-token")));

        Optional<CredentialsProvider> resolved = credentials.forCurrentUser();

        assertTrue(resolved.isPresent());
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(resolved.get().get(new URIish("https://example.test/repo.git"), user, password));
        assertEquals("oauth2", user.getValue());
        assertEquals("glpat-token", new String(password.getValue()));
    }

    @Test
    @DisplayName("Uses the username each host expects for an OAuth2 token")
    void usernameFollowsTheProvider() throws URISyntaxException {
        authenticateOAuth2User("github");
        var credentials = new OAuth2UserGitCredentials(properties(true),
                provider(serviceWithToken("github", "gho-token", Instant.now().plusSeconds(3600))));

        Optional<CredentialsProvider> resolved = credentials.forCurrentUser();

        assertTrue(resolved.isPresent());
        CredentialItem.Username user = new CredentialItem.Username();
        CredentialItem.Password password = new CredentialItem.Password();
        assertTrue(resolved.get().get(new URIish("https://example.test/repo.git"), user, password));
        // GitHub refuses GitLab's "oauth2" username
        assertEquals("x-access-token", user.getValue());
        assertEquals("gho-token", new String(password.getValue()));
    }

    @Test
    @DisplayName("Falls back to the service account when the feature is off")
    void disabledByDefault() {
        authenticateOAuth2User();
        var credentials = new OAuth2UserGitCredentials(properties(false), provider(serviceWithToken("glpat-token")));

        assertTrue(credentials.forCurrentUser().isEmpty());
    }

    @Test
    @DisplayName("Falls back when the request carries no OAuth2 login")
    void noOAuth2Authentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USERNAME, "n/a", List.of()));
        var credentials = new OAuth2UserGitCredentials(properties(true), provider(serviceWithToken("glpat-token")));

        assertTrue(credentials.forCurrentUser().isEmpty());
    }

    @Test
    @DisplayName("Falls back when no token was stored for the user")
    void noStoredToken() {
        authenticateOAuth2User();
        OAuth2AuthorizedClientService empty = mock(OAuth2AuthorizedClientService.class);
        var credentials = new OAuth2UserGitCredentials(properties(true), provider(empty));

        assertTrue(credentials.forCurrentUser().isEmpty());
    }

    @Test
    @DisplayName("Falls back when the token has expired, instead of failing the push")
    void expiredToken() {
        authenticateOAuth2User();
        var credentials = new OAuth2UserGitCredentials(properties(true),
                provider(serviceWithToken("glpat-token", Instant.now().minusSeconds(1))));

        assertTrue(credentials.forCurrentUser().isEmpty());
    }

    @Test
    @DisplayName("Scheduled work, with no user in context, uses the service account")
    void noAuthenticationAtAll() {
        var credentials = new OAuth2UserGitCredentials(properties(true), provider(serviceWithToken("glpat-token")));

        assertTrue(credentials.forCurrentUser().isEmpty());
    }
}
