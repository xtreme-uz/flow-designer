package uz.xtreme.flowdesigner.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AllowlistOAuth2UserService oauth2UserService;

    public SecurityConfig(AllowlistOAuth2UserService oauth2UserService) {
        this.oauth2UserService = oauth2UserService;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        var csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(csrfHandler)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/assets/**", "/*.js",
                                 "/*.css", "/*.ico", "/*.png", "/*.svg").permitAll()
                .requestMatchers("/oauth2/**", "/login/**").permitAll()
                // Which host to sign in through is not a secret, and the login
                // page needs it before a session exists
                .requestMatchers("/api/auth/provider").permitAll()
                // Container and orchestrator probes run before any login, and the
                // endpoint reports status only — never details
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("/", true)
                // Rejects accounts outside the allowlist before a session exists,
                // on both the plain OAuth2 and the OIDC user-info paths
                .userInfoEndpoint(userInfo -> userInfo
                    .userService(oauth2UserService)
                    .oidcUserService(oauth2UserService.oidcUserService()))
                .failureHandler(new OAuth2FailureHandler())
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessUrl("/")
            )
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new ApiAuthenticationEntryPoint(),
                    req -> req.getRequestURI().startsWith("/api/")
                )
            )
            .addFilterBefore(new UserIdHeaderFilter(), AuthorizationFilter.class);
        return http.build();
    }

    /**
     * Distinguishes "this account may not use Flow Designer" from every other
     * OAuth2 failure (expired state, misconfigured secret, provider outage), which
     * a blanket failureUrl would have reported as a rejection.
     */
    static class OAuth2FailureHandler extends SimpleUrlAuthenticationFailureHandler {

        private static final Logger log = LoggerFactory.getLogger(OAuth2FailureHandler.class);

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException, ServletException {
            boolean denied = exception instanceof OAuth2AuthenticationException oauth2
                    && AllowlistOAuth2UserService.ERROR_CODE.equals(oauth2.getError().getErrorCode());
            if (!denied) {
                log.warn("OAuth2 login failed: {}", exception.getMessage());
            }
            // Redirect directly: this handler is a singleton, so storing the URL
            // on it would let concurrent logins swap each other's messages
            getRedirectStrategy().sendRedirect(request, response,
                    denied ? "/?error=login_denied" : "/?error=login_failed");
        }
    }

    static class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {
        @Override
        public void commence(HttpServletRequest req, HttpServletResponse res,
                             AuthenticationException ex) throws IOException {
            res.setStatus(401);
            res.setContentType("application/json");
            res.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\"}");
        }
    }
}
