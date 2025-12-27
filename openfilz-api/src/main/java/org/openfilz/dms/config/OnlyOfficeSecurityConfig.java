package org.openfilz.dms.config;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.security.OnlyOfficeAuthenticationConverter;
import org.openfilz.dms.security.OnlyOfficeAuthenticationManager;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

/**
 * Security configuration for OnlyOffice DocumentServer endpoints.
 * These endpoints use custom HS256 JWT validation instead of OAuth2/Keycloak.
 *
 * This security chain has a higher priority (lower order number) than the default
 * OAuth2 security chain, ensuring OnlyOffice requests are handled here first.
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "onlyoffice.enabled", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class OnlyOfficeSecurityConfig {

    private final OnlyOfficeJwtService<OnlyOfficeUserInfo> jwtService;

    /**
     * Security filter chain for OnlyOffice endpoints.
     * - Uses custom HS256 JWT authentication (not OAuth2)
     * - Extracts user info from token and creates Authentication in SecurityContext
     * - Order -1 ensures this runs before the main OAuth2 security chain
     */
    @Bean
    @Order(-1) // Higher priority than default security chain
    public SecurityWebFilterChain onlyOfficeSecurityFilterChain(ServerHttpSecurity http) {
        // Create custom authentication filter
        AuthenticationWebFilter authFilter = new AuthenticationWebFilter(
                new OnlyOfficeAuthenticationManager(jwtService)
        );
        authFilter.setServerAuthenticationConverter(new OnlyOfficeAuthenticationConverter());

        return http
                // Only apply this chain to OnlyOffice endpoints
                .securityMatcher(new OrServerWebExchangeMatcher(
                        // Document download endpoint - called by OnlyOffice DocumentServer
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/documents/*/onlyoffice-download"),
                        // Callback endpoint - called by OnlyOffice DocumentServer for save events
                        new PathPatternParserServerWebExchangeMatcher("/api/v1/onlyoffice/callback/*")
                        // NOTE: /api/v1/onlyoffice/config/* is NOT here - it uses OAuth2 (called by frontend)
                ))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                // Add custom authentication filter
                .addFilterAt(authFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .build();
    }
}
