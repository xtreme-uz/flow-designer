package uz.xtreme.flowdesigner.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

/**
 * Loads the OAuth2 user and refuses the login when they are not on the
 * allowlist. Rejecting here rather than at the endpoint level means no session
 * is ever created for a user who should not reach the flows repository.
 */
@Component
public class AllowlistOAuth2UserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    private static final Logger log = LoggerFactory.getLogger(AllowlistOAuth2UserService.class);
    /**
     * Deliberately not "access_denied": providers use that for a user who
     * cancelled consent, and the two need different messages.
     */
    public static final String ERROR_CODE = "flowdesigner_account_not_allowed";

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegate = new DefaultOAuth2UserService();
    private final OidcUserService oidcDelegate = new OidcUserService();
    private final AuthProperties authProperties;

    public AllowlistOAuth2UserService(AuthProperties authProperties) {
        this.authProperties = authProperties;
        if (authProperties.isUnrestricted()) {
            log.warn("No login allowlist configured (app.auth.allowed-usernames / allowed-email-domains): "
                    + "every account the OAuth2 provider authenticates can read and write flows");
        }
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        return checkAllowed(delegate.loadUser(userRequest));
    }

    /**
     * The OIDC path is a different service: a provider registration that requests
     * the "openid" scope goes through OidcUserService and would otherwise skip the
     * allowlist entirely.
     */
    public OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        return userRequest -> (OidcUser) checkAllowed(oidcDelegate.loadUser(userRequest));
    }

    private OAuth2User checkAllowed(OAuth2User user) {
        String username = OAuth2UserAttributes.username(user);
        String email = OAuth2UserAttributes.email(user);

        if (!authProperties.isAllowed(username, email)) {
            log.warn("Rejected login for '{}': not on the allowlist", username);
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(ERROR_CODE, "This account is not allowed to use Flow Designer", null));
        }

        return user;
    }
}
