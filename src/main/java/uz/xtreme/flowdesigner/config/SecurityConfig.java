package uz.xtreme.flowdesigner.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

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
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("/", true)
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
