package org.openfilz.dms.config;

import org.openfilz.dms.controller.rest.BackchannelLogoutController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher;

/**
 * Permit-all chain for the OIDC back-channel logout endpoint: Keycloak calls it server to server
 * with no bearer token, and the signed logout token in the body is what the controller checks.
 * Ordered before the other dedicated chains (-4 download tokens, -3 public signatures, -2 upload
 * tokens, -1 OnlyOffice).
 */
@Configuration
public class BackchannelLogoutSecurityConfig {

    @Bean
    @Order(-5)
    public SecurityWebFilterChain backchannelLogoutSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .securityMatcher(new PathPatternParserServerWebExchangeMatcher(
                        BackchannelLogoutController.BACKCHANNEL_LOGOUT_PATH, HttpMethod.POST))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
                .build();
    }
}
