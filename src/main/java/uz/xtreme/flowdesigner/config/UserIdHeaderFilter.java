package uz.xtreme.flowdesigner.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Injects X-User-Id, X-User-Name, X-User-Email headers from OAuth2 principal
 * so FlowController stays unchanged.
 * Registered in SecurityConfig before AuthorizationFilter.
 */
public class UserIdHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof OAuth2User principal) {
            String username = OAuth2UserAttributes.username(principal);
            String email = OAuth2UserAttributes.email(principal);
            Map<String, String> injectedHeaders = Map.of(
                    "x-user-id", username,
                    "x-user-name", OAuth2UserAttributes.displayName(principal),
                    "x-user-email", email != null ? email : username + "@flowdesigner.local"
            );
            HttpServletRequest wrapped = new HttpServletRequestWrapper(request) {
                @Override
                public String getHeader(String name) {
                    String value = injectedHeaders.get(name.toLowerCase());
                    return value != null ? value : super.getHeader(name);
                }

                @Override
                public Enumeration<String> getHeaders(String name) {
                    String value = injectedHeaders.get(name.toLowerCase());
                    return value != null ? Collections.enumeration(List.of(value)) : super.getHeaders(name);
                }
            };
            chain.doFilter(wrapped, response);
        } else {
            chain.doFilter(request, response);
        }
    }
}
