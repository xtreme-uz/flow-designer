package uz.xtreme.flowdesigner.config;

import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * Reads user attributes without assuming a specific Git host.
 * Every OAuth2 provider names them differently (GitLab: username/avatar_url,
 * GitHub: login/avatar_url, Bitbucket: username/display_name), so each getter
 * falls back through the known aliases.
 */
public final class OAuth2UserAttributes {

    private OAuth2UserAttributes() {
    }

    public static String username(OAuth2User principal) {
        String username = firstNonBlank(principal, "username", "login", "preferred_username", "nickname");
        return username != null ? username : principal.getName();
    }

    public static String displayName(OAuth2User principal) {
        String name = firstNonBlank(principal, "name", "display_name", "full_name");
        return name != null ? name : username(principal);
    }

    public static String avatarUrl(OAuth2User principal) {
        return firstNonBlank(principal, "avatar_url", "picture", "avatar");
    }

    public static String email(OAuth2User principal) {
        return firstNonBlank(principal, "email", "public_email");
    }

    private static String firstNonBlank(OAuth2User principal, String... attributes) {
        for (String attribute : attributes) {
            Object value = principal.getAttribute(attribute);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
