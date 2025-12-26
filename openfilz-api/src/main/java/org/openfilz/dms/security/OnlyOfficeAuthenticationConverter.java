package org.openfilz.dms.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Converts OnlyOffice requests to Authentication objects.
 * Extracts the JWT token from the 'token' query parameter.
 */
public class OnlyOfficeAuthenticationConverter implements ServerAuthenticationConverter {

    private static final String TOKEN_PARAM = "token";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        // Extract token from query parameter
        String token = exchange.getRequest().getQueryParams().getFirst(TOKEN_PARAM);

        if (token == null || token.isEmpty()) {
            // No token in query param, try Authorization header for callback endpoint
            String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }
        }

        if (token == null || token.isEmpty()) {
            return Mono.empty();
        }

        // Return unauthenticated token - will be validated by AuthenticationManager
        return Mono.just(newToken(token));
    }

    protected OnlyOfficeAuthenticationToken newToken(String token) {
        return new OnlyOfficeAuthenticationToken(token);
    }
}
