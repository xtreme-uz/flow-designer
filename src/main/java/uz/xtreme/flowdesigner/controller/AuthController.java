package uz.xtreme.flowdesigner.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.xtreme.flowdesigner.config.OAuth2Properties;
import uz.xtreme.flowdesigner.config.OAuth2UserAttributes;

@RestController
@RequestMapping("/api")
public class AuthController {

    private final OAuth2Properties oauth2Properties;

    public AuthController(OAuth2Properties oauth2Properties) {
        this.oauth2Properties = oauth2Properties;
    }

    /**
     * The provider the administrator selected. Read before anyone is signed in,
     * so the login page can name the right host and send the browser to the right
     * authorization URL instead of hard-coding one.
     */
    @GetMapping("/auth/provider")
    public ProviderResponse getProvider() {
        OAuth2Properties.Provider provider = oauth2Properties.provider();
        return new ProviderResponse(
                provider.registrationId(),
                provider.displayName(),
                "/oauth2/authorization/" + provider.registrationId());
    }

    @GetMapping("/me")
    public UserInfoResponse getCurrentUser(@AuthenticationPrincipal OAuth2User principal) {
        return new UserInfoResponse(
            OAuth2UserAttributes.username(principal),
            OAuth2UserAttributes.displayName(principal),
            OAuth2UserAttributes.avatarUrl(principal),
            OAuth2UserAttributes.email(principal)       // email for commits
        );
    }

    public record ProviderResponse(
        String id,
        String displayName,
        String authorizationUrl
    ) {}

    public record UserInfoResponse(
        String username,
        String name,
        String avatarUrl,
        String email
    ) {}
}
