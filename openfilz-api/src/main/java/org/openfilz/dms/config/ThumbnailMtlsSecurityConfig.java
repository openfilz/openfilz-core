package org.openfilz.dms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.security.MtlsAuthenticationConverter;
import org.openfilz.dms.security.MtlsAuthenticationManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

/**
 * Security configuration for thumbnail source endpoint.
 * Uses mTLS client certificate authentication to allow only ImgProxy to access the endpoint.
 * <p>
 * When mTLS is enabled, only clients presenting a valid certificate matching the
 * configured DN pattern can access /api/v1/thumbnails/source/*.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.thumbnail.mtls-access.enabled", havingValue = "true")
public class ThumbnailMtlsSecurityConfig {

    private final ThumbnailProperties thumbnailProperties;

    /**
     * Security filter chain for thumbnail source endpoint with mTLS authentication.
     * Order -2 ensures this runs before other security chains.
     */
    @Bean
    @Order(-2)
    public SecurityWebFilterChain thumbnailMtlsSecurityFilterChain(ServerHttpSecurity http) {
        MtlsAuthenticationManager authManager = new MtlsAuthenticationManager(
                thumbnailProperties.getMtlsAccess().getAllowedDnPattern()
        );

        AuthenticationWebFilter authFilter = new AuthenticationWebFilter(authManager);
        authFilter.setServerAuthenticationConverter(new MtlsAuthenticationConverter());

        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher( API_PREFIX + ENDPOINT_THUMBNAILS + "/source/*"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .addFilterAt(authFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .authorizeExchange(exchanges -> exchanges.anyExchange().authenticated())
                .build();
    }
}
