package uz.xtreme.flowdesigner.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;

/**
 * Who is allowed to sign in.
 *
 * <p>OAuth2 only proves that someone holds an account on the configured
 * provider — on a public GitLab that is everyone. These lists narrow that to
 * the people who should be able to edit payment flows.
 *
 * <p>Both lists empty means "anyone the provider authenticates", which is only
 * appropriate for a self-hosted instance whose accounts are already the right
 * audience. The application logs a warning at startup in that case.
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        List<String> allowedUsernames,
        List<String> allowedEmailDomains
) {
    public AuthProperties {
        allowedUsernames = normalize(allowedUsernames);
        allowedEmailDomains = normalize(allowedEmailDomains);
    }

    private static List<String> normalize(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    public boolean isUnrestricted() {
        return allowedUsernames.isEmpty() && allowedEmailDomains.isEmpty();
    }

    /**
     * @param username the provider username (never null)
     * @param email    the provider email, may be null when the scope does not include it
     */
    public boolean isAllowed(String username, String email) {
        if (isUnrestricted()) {
            return true;
        }
        if (username != null && allowedUsernames.contains(username.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (email != null) {
            int at = email.lastIndexOf('@');
            if (at >= 0) {
                String domain = email.substring(at + 1).toLowerCase(Locale.ROOT);
                return allowedEmailDomains.contains(domain);
            }
        }
        return false;
    }
}
