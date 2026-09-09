package uz.xtreme.flowdesigner.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import uz.xtreme.flowdesigner.config.OAuth2Properties;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuthController Tests")
class AuthControllerTest {

    private static AuthController controllerFor(OAuth2Properties.Provider provider) {
        return new AuthController(new OAuth2Properties(
                provider, OAuth2Properties.Client.empty(), OAuth2Properties.Client.empty()));
    }

    @Test
    @DisplayName("GET /api/auth/provider - reports the selected provider and where to sign in")
    void reportsSelectedProvider() {
        AuthController.ProviderResponse response =
                controllerFor(OAuth2Properties.Provider.GITHUB).getProvider();

        assertEquals("github", response.id());
        assertEquals("GitHub", response.displayName());
        assertEquals("/oauth2/authorization/github", response.authorizationUrl());
    }

    @Test
    @DisplayName("GET /api/auth/provider - follows the administrator's choice")
    void followsTheChoice() {
        assertEquals("gitlab", controllerFor(OAuth2Properties.Provider.GITLAB).getProvider().id());
    }

    @Test
    @DisplayName("GET /api/me - reads GitLab's attribute names")
    void gitlabUser() {
        var principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("username", "alisher", "name", "Alisher",
                        "avatar_url", "https://gitlab.test/a.png", "email", "alisher@example.com"),
                "username");

        var response = controllerFor(OAuth2Properties.Provider.GITLAB).getCurrentUser(principal);

        assertEquals("alisher", response.username());
        assertEquals("Alisher", response.name());
        assertEquals("alisher@example.com", response.email());
    }

    @Test
    @DisplayName("GET /api/me - reads GitHub's attribute names")
    void githubUser() {
        // GitHub calls it 'login', and withholds the email when it is private
        var principal = new DefaultOAuth2User(
                AuthorityUtils.createAuthorityList("ROLE_USER"),
                Map.of("login", "alisher", "name", "Alisher",
                        "avatar_url", "https://github.test/a.png"),
                "login");

        var response = controllerFor(OAuth2Properties.Provider.GITHUB).getCurrentUser(principal);

        assertEquals("alisher", response.username());
        assertEquals("Alisher", response.name());
        assertEquals("https://github.test/a.png", response.avatarUrl());
        // The commit falls back to a generated address rather than failing
        assertNull(response.email());
    }
}
