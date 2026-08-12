package uz.xtreme.flowdesigner.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.xtreme.flowdesigner.config.OAuth2UserAttributes;

@RestController
@RequestMapping("/api")
public class AuthController {

    @GetMapping("/me")
    public UserInfoResponse getCurrentUser(@AuthenticationPrincipal OAuth2User principal) {
        return new UserInfoResponse(
            OAuth2UserAttributes.username(principal),
            OAuth2UserAttributes.displayName(principal),
            OAuth2UserAttributes.avatarUrl(principal),
            OAuth2UserAttributes.email(principal)       // email for commits
        );
    }

    public record UserInfoResponse(
        String username,
        String name,
        String avatarUrl,
        String email
    ) {}
}
