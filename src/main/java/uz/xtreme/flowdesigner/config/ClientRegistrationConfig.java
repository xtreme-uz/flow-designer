package uz.xtreme.flowdesigner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Registers the one OAuth2 provider the administrator selected.
 *
 * <p>Declaring both hosts in configuration would mean starting with a
 * half-configured registration whose login fails at the provider; building the
 * selected one here instead turns a missing client id into a startup error that
 * says which setting is missing.
 */
@Configuration
@EnableConfigurationProperties(OAuth2Properties.class)
public class ClientRegistrationConfig {

    private static final Logger log = LoggerFactory.getLogger(ClientRegistrationConfig.class);

    private static final String DEFAULT_REDIRECT_URI = "{baseUrl}/login/oauth2/code/{registrationId}";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OAuth2Properties properties) {
        OAuth2Properties.Provider provider = properties.provider();
        OAuth2Properties.Client client = properties.selectedClient();

        if (!client.isConfigured()) {
            String prefix = provider.registrationId().toUpperCase(Locale.ROOT);
            throw new IllegalStateException(
                    "Login provider '" + provider.registrationId() + "' is selected but not configured. "
                            + "Set " + prefix + "_CLIENT_ID and " + prefix + "_CLIENT_SECRET, "
                            + "or select the other provider with AUTH_PROVIDER.");
        }

        log.info("Sign-in goes through {} ({})", provider.displayName(), baseUrl(provider, client));
        return new InMemoryClientRegistrationRepository(List.of(build(provider, client)));
    }

    private static ClientRegistration build(OAuth2Properties.Provider provider, OAuth2Properties.Client client) {
        return switch (provider) {
            case GITLAB -> gitlab(client);
            case GITHUB -> github(client);
        };
    }

    private static ClientRegistration gitlab(OAuth2Properties.Client client) {
        String baseUrl = baseUrl(OAuth2Properties.Provider.GITLAB, client);
        String apiUrl = value(client.apiUrl(), baseUrl + "/api/v4");
        return common(OAuth2Properties.Provider.GITLAB, client, "read_user")
                .authorizationUri(baseUrl + "/oauth/authorize")
                .tokenUri(baseUrl + "/oauth/token")
                .userInfoUri(apiUrl + "/user")
                .userNameAttributeName("username")
                .build();
    }

    private static ClientRegistration github(OAuth2Properties.Client client) {
        String baseUrl = baseUrl(OAuth2Properties.Provider.GITHUB, client);
        // GitHub serves its API from a host of its own; on GitHub Enterprise Server
        // it is the same host under /api/v3
        String apiUrl = value(client.apiUrl(),
                "https://github.com".equals(baseUrl) ? "https://api.github.com" : baseUrl + "/api/v3");
        return common(OAuth2Properties.Provider.GITHUB, client, "read:user,user:email")
                .authorizationUri(baseUrl + "/login/oauth/authorize")
                .tokenUri(baseUrl + "/login/oauth/access_token")
                .userInfoUri(apiUrl + "/user")
                // GitHub calls the account name 'login'; 'id' is a number nobody recognises
                .userNameAttributeName("login")
                .build();
    }

    private static ClientRegistration.Builder common(OAuth2Properties.Provider provider,
                                                     OAuth2Properties.Client client,
                                                     String defaultScope) {
        return ClientRegistration.withRegistrationId(provider.registrationId())
                .clientId(client.clientId())
                .clientSecret(client.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(value(client.redirectUri(), DEFAULT_REDIRECT_URI))
                .scope(scopes(value(client.scope(), defaultScope)))
                .clientName(provider.displayName());
    }

    private static String baseUrl(OAuth2Properties.Provider provider, OAuth2Properties.Client client) {
        String fallback = provider == OAuth2Properties.Provider.GITHUB
                ? "https://github.com"
                : "https://gitlab.com";
        return stripTrailingSlash(value(client.baseUrl(), fallback));
    }

    /** Scopes are configured as one comma-separated string, spaces and all. */
    private static String[] scopes(String configured) {
        return Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(scope -> !scope.isEmpty())
                .toArray(String[]::new);
    }

    private static String value(String configured, String fallback) {
        return configured == null || configured.isBlank() ? fallback : configured.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
